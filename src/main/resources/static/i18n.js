// ---- i18n（#143） ----
// 零建置環境下的最小國際化實作：不引入任何函式庫，純物件字典 + 字串插值。
//
// 設計取捨：
//  - 兩份字典（zh-TW / en）結構完全對稱（同一組巢狀 key），方便 #145 用程式
//    掃描做 key parity 檢查（缺 key、多 key、value 型別不一致）。
//  - key 一律用「畫面區塊.語意」的巢狀物件，不用扁平字串串接，避免大檔案裡
//    key 衝突或誤植。
//  - t(key, params) 找不到 key 時，開發模式下會在 console.warn 並回傳
//    `⚠key` 這種明顯可見的 raw key 標記，方便測試與人工抓漏；不會讓整頁
//    炸掉。
//  - 語言判斷優先序：localStorage 記住的使用者選擇 > 瀏覽器語言（
//    navigator.languages 依序比對） > fallback 'zh-TW'。
//  - 只支援 zh-TW 与 en 兩者；navigator.language 若是 zh-CN / zh-HK 等其他
//    中文變體，一律視為未支援語言、fallback 到 zh-TW（不是「猜測」，是明確
//    落在支援清單之外）。
//  - html lang 屬性跟著切換，供螢幕閱讀器與瀏覽器功能（例如翻譯建議）使用。
(function () {
  const STORAGE_KEY = 'board.locale';
  const SUPPORTED_LOCALES = ['zh-TW', 'en'];
  const DEFAULT_LOCALE = 'zh-TW';

  // html lang 屬性的慣用值（zh-TW 用 BCP47 完整標籤，en 用短碼）。
  const HTML_LANG = {
    'zh-TW': 'zh-Hant-TW',
    'en': 'en',
  };

  const dictionaries = {
    'zh-TW': {
      app: {
        title: 'AI PROJECT BOARD',
      },
      conn: {
        connected: '連線中',
        reconnecting: '重新連線中',
      },
      notify: {
        blocked: 'BLOCKED 通知：已封鎖',
        on: 'BLOCKED 通知：開',
        off: 'BLOCKED 通知：關',
        hintBlocked: '瀏覽器已封鎖這個站台的通知權限。請到網址列旁的站台設定改為「允許」後再試。',
        hintOn: '任務轉為 BLOCKED 時發送桌面通知。這個視窗在前景時不發（畫面上已經看得到）。',
        hintOff: '開啟後，任務轉為 BLOCKED 會發送桌面通知，不需要一直盯著看板。',
        titleWithTask: 'BLOCKED：{title}',
        titleWithId: 'BLOCKED：任務 #{id}',
        bodyWithProject: '{project}　任務 #{id}',
        bodyWithProjectId: '專案 #{projectId}　任務 #{id}',
        unavailable: '無法發送桌面通知',
      },
      lang: {
        label: '語言',
        'zh-TW': '中文',
        'en': 'English',
      },
      relTime: {
        justNow: '剛剛',
        secondsAgo: '{n} 秒前',
        minutesAgo: '{n} 分鐘前',
        hoursAgo: '{n} 小時前',
        daysAgo: '{n} 天前',
      },
      error: {
        apiError: 'API 錯誤 {status}：{url}',
      },
      levelOne: {
        filterLabel: '專案篩選',
        queryLabel: '專案名稱前綴',
        queryPlaceholder: '輸入專案名稱',
        statusLabel: '狀態',
        statusGroupLabel: '專案狀態',
        blockedOnly: '只看 BLOCKED',
        sortLabel: '排序',
        sortUpdatedDesc: '最近更新',
        sortName: '名稱',
        sortProgress: '完成比例',
        clearFilters: '清除條件',
        onboardingLabel: '開始使用',
        onboardingTitle: '看板還是空的',
        onboardingLead: '這是正常的：專案與任務只能由 AI agent 透過 MCP 工具寫入，這裡的 REST 端點全部唯讀，不會有種子資料。照下面三步就會看到第一張卡片。',
        step1Title: '確認已接上看板',
        step1Hint: 'Claude Code 或 Codex 的 MCP 端點指向：',
        copy: '複製',
        copied: '已複製',
        copyManually: '請手動複製',
        step2Title: '請 agent 建立專案',
        step2Hint: '在 chat 裡說「建立一個叫 <專案名> 的專案」，agent 會呼叫 create_project。',
        step3Title: '請 agent 拆任務',
        step3Hint: '接著說「幫這個專案拆幾張任務卡片」，agent 會呼叫 create_tasks。',
        onboardingFoot: '建立後這一頁會即時更新，不需要重新整理。',
        noMatch: '還沒有符合條件的專案。',
        blockedBadge: '⚠ 阻塞',
        archived: 'ARCHIVED',
      },
      levelTwo: {
        back: '← 總覽',
        archivedReadOnly: 'ARCHIVED · 唯讀',
        viewSwitchLabel: '專案檢視方式',
        viewKanban: '看板',
        viewDependencies: '相依圖',
        noTasks: '這個專案還沒有任務。',
        filterLabel: '任務篩選',
        taskQueryLabel: '任務標題前綴',
        taskQueryPlaceholder: '輸入任務標題',
        categoryLabel: '主要類別',
        allCategories: '所有類別',
        advancedTrigger: '進階條件',
        clearFilters: '清除條件',
        advancedFilterLabel: '進階任務篩選',
        advancedTitle: '進階條件',
        closeAdvanced: '關閉進階條件',
        assigneeLabel: '負責人',
        allAssignees: '所有負責人',
        unassigned: '未指派',
        waitingOnly: '只看等待前置',
        claimableOnly: '只看可認領',
        claimableHelp: '可認領 = TODO，且所有前置任務已完成。',
        viewTaskAria: '查看任務 #{id}：{title}',
        waitingHint: '前置任務完成前不會被認領',
        waitingFor: '等待 {ids}',
        dependencyViewLabel: '任務相依圖',
        dependencyTitle: '相依關係',
        dependencyLead: '箭頭由前置任務指向下游任務。',
        expandFull: '展開完整圖（{count}）',
        collapsePartial: '顯示局部關係',
        loadingGraph: '正在載入相依圖…',
        reload: '重新載入',
        categoryFilterLabel: '依類別顯示或隱藏',
        showAllCategories: '全部顯示',
        longestChain: '最長相依鏈：{chain}',
        partialScope: '目前顯示可進行工作及其直接關係；可展開完整圖。',
        noEdges: '尚未設定任務相依；以下是所有獨立任務。',
        noVisibleNodes: '目前的篩選條件或類別設定沒有可顯示的任務。',
        claimable: '可認領',
        legendLabel: '相依圖圖例',
        legendWaiting: '等待前置',
        legendLongest: '最長鏈',
        detailTaskLabel: 'TASK #{id}',
        closeDetail: '關閉任務詳情',
        loadingDetail: '正在載入任務詳情',
        description: '說明',
        noDescription: '沒有說明',
        currentBlocker: '目前阻塞',
        blockedBy: 'BLOCKED BY',
        blockedAt: 'BLOCKED AT',
        duration: 'DURATION',
        blockingTasks: 'BLOCKING TASKS',
        prerequisites: '前置任務',
        none: '無',
        downstreamTasks: '下游任務',
        completionEvidence: '完成證據',
        lastCompletionEvidence: '最近一次完成證據',
        completedBy: 'COMPLETED BY',
        completedAt: 'COMPLETED AT',
        changedFiles: 'CHANGED FILES',
        commitOrPr: 'COMMIT / PR',
        fullHistory: '完整歷史',
        noHistory: '沒有歷史紀錄',
        createdAs: '建立為 {status}',
        assignee: 'ASSIGNEE',
        claimed: 'CLAIMED',
        updated: 'UPDATED',
        sort: 'SORT',
        emptyValue: '—',
      },
      blockerReason: {
        DEPENDENCY: '等待任務',
        USER_INPUT: '等待使用者',
        TECHNICAL: '技術問題',
        EXTERNAL: '外部因素',
        OTHER: '其他原因',
        unknown: '未分類',
      },
      duration: {
        lessThanMinute: '不到 1 分鐘',
        minutes: '{n} 分鐘',
        hoursMinutes: '{h} 小時 {m} 分鐘',
        daysHours: '{d} 天 {h} 小時',
      },
    },
    'en': {
      app: {
        title: 'AI PROJECT BOARD',
      },
      conn: {
        connected: 'Connected',
        reconnecting: 'Reconnecting',
      },
      notify: {
        blocked: 'BLOCKED alerts: blocked',
        on: 'BLOCKED alerts: on',
        off: 'BLOCKED alerts: off',
        hintBlocked: 'Notifications for this site are blocked by your browser. Allow them from the site settings next to the address bar, then try again.',
        hintOn: 'Sends a desktop notification when a task turns BLOCKED. Skipped while this window is in the foreground (you can already see it on screen).',
        hintOff: 'Turn on to get a desktop notification when a task turns BLOCKED, so you do not have to watch the board.',
        titleWithTask: 'BLOCKED: {title}',
        titleWithId: 'BLOCKED: Task #{id}',
        bodyWithProject: '{project}  Task #{id}',
        bodyWithProjectId: 'Project #{projectId}  Task #{id}',
        unavailable: 'Could not send desktop notification',
      },
      lang: {
        label: 'Language',
        'zh-TW': '中文',
        'en': 'English',
      },
      relTime: {
        justNow: 'just now',
        secondsAgo: '{n}s ago',
        minutesAgo: '{n}m ago',
        hoursAgo: '{n}h ago',
        daysAgo: '{n}d ago',
      },
      error: {
        apiError: 'API error {status}: {url}',
      },
      levelOne: {
        filterLabel: 'Project filters',
        queryLabel: 'Project name prefix',
        queryPlaceholder: 'Type a project name',
        statusLabel: 'Status',
        statusGroupLabel: 'Project status',
        blockedOnly: 'BLOCKED only',
        sortLabel: 'Sort',
        sortUpdatedDesc: 'Recently updated',
        sortName: 'Name',
        sortProgress: 'Progress',
        clearFilters: 'Clear filters',
        onboardingLabel: 'Get started',
        onboardingTitle: 'The board is empty',
        onboardingLead: 'That is expected: projects and tasks can only be written through MCP tools by an AI agent — every REST endpoint here is read-only, so there is no seed data. Follow the three steps below to see the first card.',
        step1Title: 'Confirm the board is connected',
        step1Hint: 'Point the Claude Code or Codex MCP endpoint at:',
        copy: 'Copy',
        copied: 'Copied',
        copyManually: 'Copy manually',
        step2Title: 'Ask the agent to create a project',
        step2Hint: 'Say "create a project named <name>" in chat; the agent will call create_project.',
        step3Title: 'Ask the agent to break down tasks',
        step3Hint: 'Then say "split this project into a few task cards"; the agent will call create_tasks.',
        onboardingFoot: 'This page updates live once created — no need to refresh.',
        noMatch: 'No projects match the current filters.',
        blockedBadge: '⚠ Blocked',
        archived: 'ARCHIVED',
      },
      levelTwo: {
        back: '← Overview',
        archivedReadOnly: 'ARCHIVED · Read-only',
        viewSwitchLabel: 'Project view mode',
        viewKanban: 'Kanban',
        viewDependencies: 'Dependency graph',
        noTasks: 'This project has no tasks yet.',
        filterLabel: 'Task filters',
        taskQueryLabel: 'Task title prefix',
        taskQueryPlaceholder: 'Type a task title',
        categoryLabel: 'Category',
        allCategories: 'All categories',
        advancedTrigger: 'Advanced filters',
        clearFilters: 'Clear filters',
        advancedFilterLabel: 'Advanced task filters',
        advancedTitle: 'Advanced filters',
        closeAdvanced: 'Close advanced filters',
        assigneeLabel: 'Assignee',
        allAssignees: 'All assignees',
        unassigned: 'Unassigned',
        waitingOnly: 'Waiting on prerequisites only',
        claimableOnly: 'Claimable only',
        claimableHelp: 'Claimable = TODO and all prerequisite tasks are DONE.',
        viewTaskAria: 'View task #{id}: {title}',
        waitingHint: 'Cannot be claimed until prerequisite tasks are done',
        waitingFor: 'Waiting on {ids}',
        dependencyViewLabel: 'Task dependency graph',
        dependencyTitle: 'Dependencies',
        dependencyLead: 'Arrows point from prerequisite tasks to downstream tasks.',
        expandFull: 'Expand full graph ({count})',
        collapsePartial: 'Show local view',
        loadingGraph: 'Loading dependency graph…',
        reload: 'Reload',
        categoryFilterLabel: 'Show or hide by category',
        showAllCategories: 'Show all',
        longestChain: 'Longest dependency chain: {chain}',
        partialScope: 'Showing actionable work and its direct relations; you can expand the full graph.',
        noEdges: 'No dependencies configured yet; all tasks below are independent.',
        noVisibleNodes: 'No tasks match the current filters or category settings.',
        claimable: 'Claimable',
        legendLabel: 'Dependency graph legend',
        legendWaiting: 'Waiting on prerequisites',
        legendLongest: 'Longest chain',
        detailTaskLabel: 'TASK #{id}',
        closeDetail: 'Close task detail',
        loadingDetail: 'Loading task detail',
        description: 'Description',
        noDescription: 'No description',
        currentBlocker: 'Current blocker',
        blockedBy: 'BLOCKED BY',
        blockedAt: 'BLOCKED AT',
        duration: 'DURATION',
        blockingTasks: 'BLOCKING TASKS',
        prerequisites: 'Prerequisites',
        none: 'None',
        downstreamTasks: 'Downstream tasks',
        completionEvidence: 'Completion evidence',
        lastCompletionEvidence: 'Latest completion evidence',
        completedBy: 'COMPLETED BY',
        completedAt: 'COMPLETED AT',
        changedFiles: 'CHANGED FILES',
        commitOrPr: 'COMMIT / PR',
        fullHistory: 'Full history',
        noHistory: 'No history yet',
        createdAs: 'Created as {status}',
        assignee: 'ASSIGNEE',
        claimed: 'CLAIMED',
        updated: 'UPDATED',
        sort: 'SORT',
        emptyValue: '—',
      },
      blockerReason: {
        DEPENDENCY: 'Waiting on task',
        USER_INPUT: 'Waiting on user',
        TECHNICAL: 'Technical issue',
        EXTERNAL: 'External factor',
        OTHER: 'Other reason',
        unknown: 'Uncategorized',
      },
      duration: {
        lessThanMinute: 'less than 1 minute',
        minutes: '{n} min',
        hoursMinutes: '{h}h {m}m',
        daysHours: '{d}d {h}h',
      },
    },
  };

  function detectLocale() {
    try {
      const stored = window.localStorage.getItem(STORAGE_KEY);
      if (stored && SUPPORTED_LOCALES.includes(stored)) return stored;
    } catch (e) {
      // localStorage 不可用（例如部分隱私模式）時忽略，改看瀏覽器語言。
    }

    const candidates = (navigator.languages && navigator.languages.length)
      ? navigator.languages
      : [navigator.language].filter(Boolean);

    for (const raw of candidates) {
      if (!raw) continue;
      const lower = raw.toLowerCase();
      if (lower.startsWith('zh')) {
        // zh-TW / zh-Hant(-*) 視為支援的正體中文；zh-CN、zh-Hans、zh 等其他
        // 中文變體不強行對應，落回 DEFAULT_LOCALE（zh-TW），這本來就是預設值。
        if (lower.includes('tw') || lower.includes('hant')) return 'zh-TW';
        return DEFAULT_LOCALE;
      }
      if (lower.startsWith('en')) return 'en';
    }
    return DEFAULT_LOCALE;
  }

  function persistLocale(locale) {
    try {
      window.localStorage.setItem(STORAGE_KEY, locale);
    } catch (e) {
      // 存不起來只影響下次是否記得住，不阻斷本次切換。
    }
  }

  function applyHtmlLang(locale) {
    document.documentElement.setAttribute('lang', HTML_LANG[locale] || locale);
  }

  function resolvePath(dict, key) {
    return key.split('.').reduce((acc, part) => (acc && typeof acc === 'object') ? acc[part] : undefined, dict);
  }

  function interpolate(template, params) {
    if (!params) return template;
    return template.replace(/\{(\w+)\}/g, (match, name) =>
      Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : match);
  }

  const state = Vue.reactive({ locale: detectLocale() });
  applyHtmlLang(state.locale);

  function t(key, params) {
    const dict = dictionaries[state.locale] || dictionaries[DEFAULT_LOCALE];
    let value = resolvePath(dict, key);
    if (value === undefined) {
      // fallback 到預設語言，避免單一語言漏翻直接開天窗。
      value = resolvePath(dictionaries[DEFAULT_LOCALE], key);
    }
    if (value === undefined) {
      console.warn(`[i18n] missing key: ${key}`);
      return `⚠${key}`;
    }
    return typeof value === 'string' ? interpolate(value, params) : value;
  }

  function setLocale(locale) {
    if (!SUPPORTED_LOCALES.includes(locale) || locale === state.locale) return;
    state.locale = locale;
    persistLocale(locale);
    applyHtmlLang(locale);
  }

  window.__i18n = {
    t,
    setLocale,
    state,
    SUPPORTED_LOCALES,
    dictionaries,
  };
})();
