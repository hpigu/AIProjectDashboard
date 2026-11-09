# 排程模型回歸驗證

執行 `node --test scripts/worker-scheduling-regression/regression.test.js`，需 Node.js 與 Git；不連線看板、不啟動伺服器。

模型驗證平台 slots（含 leader）、預留與認領不重複計數、握手序列、相依與資源衝突、失聯保留 claim 及 BLOCKED 恢復。角色與專案均無配額。這只是排程決策模型，不能宣稱已執行 Claude/Codex 的真實多代理派工。

Git fixture 在系統 temp 建立 repository 與 worktrees，驗證 dirty 成果保留及明確指定的整合門檻；其中 dev/task 名稱只屬此例，不是通用分支規則。成功與失敗都保留結果，不碰正式資料。

真實瀏覽器、SQLite、HTTP MCP 與獨立代理審查交接的限定範例見 [驗收流程](../../docs/acceptance-demo-flow.md)。完整真實多 worker 的長時間啟動、失聯接手及公司整合環境仍須另外演練；此模型不替代它。
