# 本次實作與驗證摘要

日期：2026-11-09。這是 repo 開發成果與隔離驗證，不是正式服務升級或公司系統驗收。

## 已完成

- Java/H2 與 Workers/D1 移除所有角色／專案配額，保留 CAS、三次重試、token、交易與相依保護。
- 新增追加式簽章驗收紀錄：專案 Git policy、版本化需求契約、受控 RUN、獨立 REVIEW、人類 EXCEPTION、派工／交接、整合及發布分開保存。
- 實作回報 `report_implementation` 只存代理聲明；正式 `complete_task` 檢查需求、測試、證據版本、獨立核准及專案整合規則。直接改 DONE 已封鎖；歷史 DONE 不提升證據可信度，未驗證／已失效的前置也不解鎖下游。
- 可信入口有專案／操作／runner scope、不可變 codeRef、不同金鑰與職責分離、需求摘要、重放／版本衝突保護。管理工具另有獨立憑證／裝置白名單及授權嘗試稽核。
- 看板摘要顯示需求、已通過案例、阻擋原因、待決問題、推測、各階段與派工過期提示；證據展開閱讀，成本／資源取不到就顯示未知。
- 兩套 plugin、角色初始指引、QA 流程卡與每專案 Git policy 範例更新。CI 新增 Workers 測試，Dependabot 新增 npm 每週更新檢查。
- 新增加密備份與隔離還原工具，缺少目的地或驗證失敗時以非零退出提示；實際 H2 快照還原重開查詢已有隔離證據。

## 實際驗證

| 驗證 | 結果與證據 |
|---|---|
| Java 最新完整 `clean package` | 381 tests，0 failures/errors/skipped；僅在 `/tmp/board-workflow-verify-JEpFZk` 建置。報告 `/tmp/board-workflow-java-final.log` |
| Workers 完整 suite | 80 tests 通過，包含真實 workerd/D1、HTTP MCP、專案流程版本與管理授權；`/tmp/board-workflow-workers-final.log` |
| 角色與 plugin | `scripts/check-plugin-parity.sh`、兩份 skill 一致、Java/Workers 五角色 seed 逐字及 SQL 執行驗證通過 |
| 排程模型／Git fixture | 5/5 通過；模型與真正 AI 代理流程明確分開，無角色配額 |
| 備份安全 | 6/6 通過：加解密、錯 key、篡改、路徑穿越、權限與正式資料拒絕 |
| 真 H2 還原 | 隔離 H2 建表/插入 → 真 ZIP → AES-GCM → temp 還原 → JDBC 重開，來源與還原 row 一致；`/var/folders/zm/cfhkzrvx79g27ht131f3t4nm0000gn/T/ai-board-real-h2-drill-jEaob7/actual-h2-result.json` |
| 真瀏覽器＋獨立代理交接 | 下方限定範例完整 PASSED，18 項操作／門檻檢查；包含另一個 live reviewer 重新核對規格與證據，沒有沿用舊核准 |

範例結果：`/var/folders/zm/cfhkzrvx79g27ht131f3t4nm0000gn/T/board-acceptance-demo-oDTDj4/result.json`。
範例程式 codeRef：`8220c14dab0fa26ecd83329e5cc94487a41660f2aa2ab12b47dcb75c40a24078`。
同目錄保存 execution.json、review-request.json、independent-review.json、browser.png、board.png、fixture.sqlite。
流程卡、五個機制檔案與執行報告分別有內容雜湊；詳細重跑方式見 [隔離範例流程](acceptance-demo-flow.md)。

七種情境：正常新增、重複錯誤、格式邊界、reader 操作失敗、角色儲存／重新整理、editor 後續流程、禁止操作 403 且無額外副作用。額外證明自稱 PASSED、未決需求、缺證據、未授權 RUN、偽簽章、錯版本、FAILED、漏驗及缺獨立審查均不能完成。核准後再由真實看板 UI 檢查需求／案例摘要並輸出 PASSED。

## 專案 Git 流程可操作驗證

已補齊規劃對話 skill、專案草稿／核准版本、owner 互動工具、唯讀網頁面板及後續 CONTRACT 的 workflowVersion 約束。空專案即可設定，不必先建立卡片；缺項／待決問題不可核准，草稿不覆蓋有效設定，新核准版本使舊契約重新確認適用性。

- Java 全套 381、Workers 全套 80、owner helper 13 項皆通過；plugin parity 與 `git diff --check` 通過。
- 真隔離 Java `:8093` ＋真 Chrome 完成 9 項操作紀錄：空專案入口、複製規劃指令、部分草稿／SSE、補齊、owner helper HTTP 往返、重新整理後七欄持久化／重用、修改不影響 active、未授權 403／唯讀 POST 405、讀取失敗重試。兩次 helper 核准各有紀錄；細項以 result.json 為準。
- 證據：`/var/folders/zm/cfhkzrvx79g27ht131f3t4nm0000gn/T/board-workflow-browser-u2R83X/result.json`，同目錄包含桌機／手機截圖、程式與截圖 hash；served app SHA256 為 `1948261cd4238061b6836385b2f4bd12004743d7196157c660949c7f90c1a77b`。已人工檢視桌機有效設定與手機缺項畫面。
- 畫面腳本透過固定 fixture IO **模擬人類輸入**，驗證協定與狀態，不構成真實人類核准。未部署／更新已安裝 plugin、未配置正式 owner 憑證或公司規則；首次正式使用仍需在獨立 owner 環境配置。
- 重跑與操作方式見 [規劃對話與畫面驗證](project-git-workflow.md)及 [owner 核准工具](project-workflow-approval.md)。

## 適用範圍與待設定

- 範例登入是角色流程模擬，writes 是記憶體計數，並非公司登入或完整業務資料模型；它不代表真實公司系統、完整邊界矩陣或所有需求都正確。範例 fixture 明確允許管理操作，真正管理授權由另外的後端測試驗證。
- 本次有真實瀏覽器、SQLite、HTTP MCP 及獨立代理審查交接；尚未演練完整多 worker 長時間運作、失聯接手及公司整合環境。排程模型不冒稱為這些端到端驗證。
- HMAC 核對設定身分與內容完整性，不會自行證明內容真實；各工作專案仍需部署與受測程式隔離的受控 runner／簽署端。看板不執行任意 shell，也不下載查核 artifacts 或連外監看需求 repo／Git head；外部版本變更需導入新契約重新判定。
- 公司 Git flow、必要檢查、審查／合併權限、可信簽署者及管理 client 憑證隔離尚待維護者設定；未設定時不猜分支、不合併、不接受未驗收結案。管理憑證不是公司 SSO；Java 稽核只辨識設定憑證，Workers 辨識驗證裝置。
- 備份目的地、離機傳輸／排程與通知通道尚未設定。本次沒有完成離機備份；隔離 H2 row 演練不等於完整正式看板災難復原或公司驗收。
- Amazon Bedrock 模型、日誌、區域與保留設定未查核，也未改公司 AWS。
- repo 角色指引已修改，既有資料庫角色沒有同步。沒有 commit、push、PR、部署或重啟正式服務；沒有操作正式埠號、資料庫、日誌或執行中的 jar。

設定與取捨：[驗收協定](acceptance-protocol.md)、[管理授權](authorization-boundaries.md)、[Git／流程卡模板](project-workflow-template.md)、[備份還原](backup-recovery.md)。

安全設計參考：[GitHub artifact attestations](https://docs.github.com/en/actions/concepts/security/artifact-attestations)、[GitHub CLI attestation verification](https://cli.github.com/manual/gh_attestation_verify)。本實作沒有宣稱整合或驗證 GitHub attestation／公司 CI；它使用本文件列明的專案範圍 HMAC 接收邊界。
