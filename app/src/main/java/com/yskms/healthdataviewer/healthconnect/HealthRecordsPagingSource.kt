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
) : PagingSource<Int, T>() {
    private val tokenMutex = Mutex()
    private val pageStartTokens = mutableListOf<String?>(null)

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> {
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
                data = page.records,
                prevKey = if (pageIndex == 0) null else pageIndex - 1,
                nextKey = if (page.nextPageToken != null) pageIndex + 1 else null,
            )
        } catch (e: RemoteException) {
            LoadResult.Error(e)
        } catch (e: IOException) {
            LoadResult.Error(e)
        } catch (e: SecurityException) {
            LoadResult.Error(e)
        }
    }

    // Health Connectのtokenはランダムアクセスできないため、invalidate後は常にページ0から再開する
    // （6.1参照。任意の位置に対応するキーを算出しようとしない）。
    override fun getRefreshKey(state: PagingState<Int, T>): Int? = null

    companion object {
        private const val TAG = "HealthRecordsPagingSource"
    }
}
