# 隔離驗收範例流程卡 v1

狀態：本 repo 的測試規格；不是公司業務規則。需求來源：本次開發要求中的新增使用者、角色、權限、登入及後續操作範例。適用版本：每次執行由 `scripts/acceptance-demo/run.mjs` 內容 SHA-256 識別。

已確認的範例規則：

- 合法帳號為小寫英文字開頭、其餘小寫英文字與數字，長度 1–20。新增後預設 reader；重複帳號回 409，格式錯誤回 400，均不新增額外資料。
- reader 可模擬登入，不能新增工作紀錄；伺服器回 403 且寫入數不變。
- 將該帳號指派為 editor，SQLite 內角色必須改變，重新整理頁面後仍為 editor。
- 用新帳號的 editor 身分可新增一筆工作紀錄；禁止刪除系統設定，伺服器回 403 且既有資料不變。
- 所有資料都是每次新建的隔離 fixture，沒有正式帳號、公司登入或正式客戶資料。登入是此 fixture 的角色流程模擬，並非正式身分驗證系統。

案例：NORMAL 新增；ERROR 重複；BOUNDARY 格式邊界；FAILURE reader 寫入失敗；PERSISTENCE 角色存檔與重新整理；DOWNSTREAM editor 使用後續流程；PERMISSION editor 禁止操作。每個案例必須有實際操作、伺服器結果及資料斷言，截圖只是輔助。

待確認事項：真實工作專案的帳號規則、權限矩陣與登入方式；本範例沒有推定其正確值。這些示範規則只用於驗收機制測試。

## 重跑

`node scripts/acceptance-demo/run.mjs` 使用已安裝的 Chrome，透過 CDP 真實操作畫面、HTTP MCP 與獨立 SQLite 檔案。需要 Node 22 以上，不安裝前端套件。可用 `CHROME_BIN` 指定已安裝的 Chrome／Chromium headless 可執行檔。每次輸出到系統 temp 新目錄，不清除資料。

`--independent-review` 會在證據完成後輸出 review-request.json 路徑，等待另一個 reviewer 查閱本流程卡、原始程式、execution.json 與截圖，寫入要求路徑的 independent-review.json（codeRef、subjectDigest、decision、reason）。缺少／拒絕核准就失敗。這個模式有真正的跨代理交接，但仍是本地範例，不是公司的多代理整合環境。

不帶此參數是固定測試模型：runner 與 reviewer 為分開簽署權限，review 是範例預設結論，不能稱為真實獨立代理驗收。`--independent-review` 也只驗證 reviewer 提供的結論與版本，身份由本次協作環境確認，不等於公司 SSO。

保存 execution.json、result.json、browser.png、board.png 與 fixture.sqlite。報告指明範例程式 SHA-256；`result.json` 只有所有門檻與畫面斷言通過才寫 PASSED。簽署金鑰每次隨機產生、只留在固定監督程式記憶體，不注入頁面或 Chrome 子行程。這個固定程式不接受任意 shell／受測程式參數，也不是通用沙箱；各專案仍需獨立部署受控 runner。
