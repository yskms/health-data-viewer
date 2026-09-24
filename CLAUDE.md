# Health Connect Viewer

Health Connectに保存済みのデータを読み取り専用で可視化・検証するAndroidアプリ。
現在は企画・PoC前の段階。

-   要件: [docs/health-connect-viewer-research-requirements.md](docs/health-connect-viewer-research-requirements.md)
-   技術方針（確定事項とPoCで確定する候補の区別）: [docs/tech-stack.md](docs/tech-stack.md)
-   `docs/` の画像はイメージ用のモックアップとアイコン案。細部（日付・文言など）は仕様ではない
-   リポジトリは公開。`docs/` は競合分析・収益化方針を含むため `.gitignore` で除外し、ローカルのみで管理している。コミットに含めないこと（GitHub上では上記リンクは切れる）

## 変えてはいけない原則

-   **Health ConnectはRead-only。** 書き込み権限の宣言、Recordの追加・編集・削除は一切しない
-   **Rawレコードはアプリ独自に重複排除・重複判定しない。** 重複も含めてすべて表示することがこのアプリの目的
-   **Health Connectの健康データは広告・Analytics・ログに一切流さない。** Google Playのポリシー違反になる。広告のコードから健康データ層を参照させない
-   **1Y（年）とALL（全期間）はMVP必須。** 簡略化のために削らない
-   AdMobを入れているため、ストア文言などで「No cloud」「No network」は使わない。「健康データを外部送信しない」と表現する
-   広告は画面下部のバナーのみ。表示してはいけない画面の一覧は要件 §17
-   Android専用。iOS対応やクロスプラットフォーム化を提案しない

## Health Connectで誤解しやすい点

-   Aggregateで公式の重複処理が効くのはActivity / Sleepのみ。体重などのAggregate平均には、複数ソースの同一測定がそのまま含まれる
-   ソース別の**レコード件数**はAggregateでは取れない（全件走査が必要）
-   Health Connectが動くのはAndroid 9（API 28）以上。SDKのminSdk 26に合わせない
-   グラフの集計方法はデータ型・期間ごとに決め、画面にも明示する（例: 「月平均」）
