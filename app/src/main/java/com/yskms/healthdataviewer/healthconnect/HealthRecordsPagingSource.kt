package com.yskms.healthdataviewer.healthconnect

import android.os.RemoteException
import android.util.Log
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

// loadPage()が1回のHealth Connect呼び出しで返すページ。nextPageTokenがnullなら最終ページ。
data class HealthRecordsPage<T : Any>(val records: List<T>, val nextPageToken: String?)

// WBS 6.3コードレビュー指摘: LazyColumnのitems()にkeyを渡さない場合、Composeは画面内の各行を
// 「現在の位置（index）」だけで識別する。append中にPagingConfig.maxSizeで先頭ページが破棄されると
// 後続の全レコードのindexが詰まり、prepend中に先頭へページが挿入されるとindexがずれる。どちらも
// 実際のスクロール位置（LazyListState）はindexベースのまま据え置かれるため、ユーザーがスクロール
// した量だけ「見た目には連続しているが中身が入れ替わった」状態になり、一部のレコードを画面に
// 表示しないまま通過してしまいうる（全件を検証可能にするという目的に反する）。
//
// これを避けるには、レコードの中身（重複しうるmetadata.id）に頼らない安定したkeyが必要。
// 「このレコードはページpageIndexのindexInPage番目」という位置はtoken境界が固定されている限り
// 破棄・再読込をまたいでも変わらないため、(pageIndex, indexInPage)をkeyに使えば、evictionで
// 一時的に画面から消えたレコードが再読込で戻ってきたときも同じ行として認識され、LazyListState側で
// スクロール位置が正しく追従する（公式ドキュメント通りのkeyの役割）。
data class PagedRecord<T : Any>(val pageIndex: Int, val indexInPage: Int, val value: T)

// WBS 6.3: 全件を1つのListに溜め込む旧実装（readAllWeightRecords()等）がHeart RateでOOMを
// 起こした問題（lessons.md 6.7）への対応。画面に見えている分だけ保持するようPaging3でラップする。
//
// Health Connectのpageは「次のページへ進む」token（前方向限定）しか提供せず、破棄したページに
// 戻るためのtokenは提供しない。そこでPaging3のkeyには連番のページ番号（Int）を使い、インスタンス
// 内に`pageStartTokens`（index i = ページiを読むために渡すtoken。0番目は常にnull）を保持する。
// append方向のload呼び出しは常に直前のload結果が返したnextKeyを辿るだけなので、未訪問ページへの
// 直接アクセスは正常なキー連鎖の中では発生しない（getRefreshKey()が常にnullを返すことで、
// invalidate後もページ0から再開させ、この前提を保つ）。PagingConfig.maxSizeで表示用データが
// 破棄されても、この軽量なtoken一覧自体はこのPagingSourceインスタンスが存続する限り保持されるため、
// 破棄済みページへ戻る操作（prepend）も保持したtokenで再取得できる（docs/wbs.md 6.3の未決事項への対応）。
//
// append/prependは別方向から並行して進行しうるため、pageStartTokensへのアクセスはMutexで直列化する。
//
// 呼び出し元は必ずPagingConfig.initialLoadSizeをpageSizeと同じ値に指定すること。Paging3の
// initialLoadSizeの既定値はpageSize * 3で、指定しないと初回load（REFRESH、pageIndex 0）だけ
// pageSizeの3倍のレコードを読み、pageStartTokens[1]にはその3倍分進んだ地点のtokenが保存される。
// その後maxSizeでpage 0がUIから破棄され、ユーザーが上にスクロールして戻るとprepend
// load(key=0, loadSize=pageSize)が呼ばれるが、このときは通常のpageSizeでしか再取得できないため、
// 「3倍読んだ分」と「pageSizeだけ読み直した分」の差（例: pageSize=20なら21〜60件目）が
// どちらの読み込みにも含まれずそのまま欠落する（レビュー指摘）。initialLoadSizeをpageSizeに
// 揃えれば、REFRESH/APPEND/PREPENDのいずれも同じloadSizeになり、「1ページ = 1 loadSize」という
// pageStartTokensの前提が崩れない。
class HealthRecordsPagingSource<T : Any>(
    private val loadPage: suspend (pageToken: String?, pageSize: Int) -> HealthRecordsPage<T>,
) : PagingSource<Int, PagedRecord<T>>() {
    private val tokenMutex = Mutex()
    private val pageStartTokens = mutableListOf<String?>(null)

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, PagedRecord<T>> {
        val pageIndex = params.key ?: 0
        val (visited, startToken) =
            tokenMutex.withLock {
                if (pageIndex < pageStartTokens.size) true to pageStartTokens[pageIndex] else false to null
            }
        if (!visited) {
            // 正常なキー連鎖では到達しないはずの分岐（load()の呼び出し元は常にこのインスタンス自身が
            // 過去に返したprevKey/nextKeyしか渡さない）。到達した場合はPaging3側かこの実装のバグを
            // 意味するため、握りつぶさずログで検知できるようにする。
            Log.w(TAG, "Unvisited page requested: pageIndex=$pageIndex")
            return LoadResult.Invalid()
        }
        return try {
            val page = loadPage(startToken, params.loadSize)
            tokenMutex.withLock {
                if (pageStartTokens.size == pageIndex + 1) {
                    pageStartTokens.add(page.nextPageToken)
                }
            }
            LoadResult.Page(
                data = page.records.mapIndexed { indexInPage, record -> PagedRecord(pageIndex, indexInPage, record) },
                prevKey = if (pageIndex == 0) null else pageIndex - 1,
                nextKey = if (page.nextPageToken != null) pageIndex + 1 else null,
            )
        } catch (e: RemoteException) {
            LoadResult.Error(e)
        } catch (e: IOException) {
            LoadResult.Error(e)
        } catch (e: SecurityException) {
            LoadResult.Error(e)
        } catch (e: IllegalArgumentException) {
            // WBS 6.3コードレビュー指摘: prepend（破棄済みページの再読込）は、pageStartTokensに
            // 保持したtokenを取得からしばらく経ってから再利用する。Health Connectのpageトークンの
            // 有効期限・安定性は未確認（lessons.md旧6.7時点から要検証のまま）で、期限切れ・無効化された
            // tokenに対してIllegalArgumentException系の例外が投げられる可能性がある。これをクラッシュ
            // させず読み込みエラーとして表示できるようにする。ここ以外のHealthConnectManagerの関数は
            // tokenを取得後すぐに同じループ内で使い切るだけで、このように「保持して後で再利用する」
            // 経路がないため、同じ対応はしていない。
            Log.w(TAG, "Possibly stale pageToken: pageIndex=$pageIndex", e)
            LoadResult.Error(e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Possibly stale pageToken: pageIndex=$pageIndex", e)
            LoadResult.Error(e)
        }
    }

    // Health Connectのtokenはランダムアクセスできないため、invalidate後は常にページ0から再開する
    // （6.1参照。任意の位置に対応するキーを算出しようとしない）。
    override fun getRefreshKey(state: PagingState<Int, PagedRecord<T>>): Int? = null

    companion object {
        private const val TAG = "HealthRecordsPagingSource"
    }
}
