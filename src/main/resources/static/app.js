const { createApp, ref, reactive, computed, onMounted, onBeforeUnmount, nextTick } = Vue;

const STATUS_ORDER = ['DONE', 'IN_PROGRESS', 'BLOCKED', 'TODO'];
const RAIL_FLASH_CLASS = {
  'TODO': 'flash-todo',
  'IN_PROGRESS': 'flash-progress',
  'BLOCKED': 'flash-blocked',
  'DONE': 'flash-done',
};

function relativeTime(isoString) {
  if (!isoString) return '';
  const then = new Date(isoString).getTime();
  const now = Date.now();
  const diffSec = Math.floor((now - then) / 1000);
  if (diffSec < 30) return '剛剛';
  if (diffSec < 60) return `${diffSec} 秒前`;
  const diffMin = Math.floor(diffSec / 60);
  if (diffMin < 60) return `${diffMin} 分鐘前`;
  const diffHour = Math.floor(diffMin / 60);
  if (diffHour < 24) return `${diffHour} 小時前`;
  const diffDay = Math.floor(diffHour / 24);
  return `${diffDay} 天前`;
}

async function fetchJson(url) {
  const res = await fetch(url);
  if (!res.ok) {
    throw new Error(`API 錯誤 ${res.status}：${url}`);
  }
  return res.json();
}

const LevelOne = {
  props: ['projects'],
  emits: ['select'],
  template: `
    <div>
      <div v-if="projects.length === 0" class="empty-state">
        還沒有專案。在 Claude Code 或 Codex 裡說「幫我規劃一個⋯⋯」就會出現在這裡。
      </div>
      <div v-else>
        <button
          v-for="p in projects"
          :key="p.id"
          class="project-row"
          :class="{ blocked: p.hasBlocked }"
          @click="$emit('select', p.id)"
        >
          <div class="project-row-top">
            <div class="project-name">
              {{ p.name }}
              <span v-if="p.hasBlocked" class="blocked-badge">⚠ 阻塞</span>
            </div>
            <div class="project-meta">
              <span>{{ p.counts.DONE }}/{{ p.total }}</span>
              <span>{{ relTime(p.updatedAt) }}</span>
            </div>
          </div>
          <div class="segbar">
            <div class="seg-done" :style="segStyle(p, 'DONE')"></div>
            <div class="seg-progress" :style="segStyle(p, 'IN_PROGRESS')"></div>
            <div class="seg-blocked" :style="segStyle(p, 'BLOCKED')"></div>
            <div class="seg-todo" :style="segStyle(p, 'TODO')"></div>
          </div>
        </button>
      </div>
    </div>
  `,
  methods: {
    relTime(iso) { return relativeTime(iso); },
    segStyle(p, key) {
      const total = p.total || 1;
      const value = p.counts[key] || 0;
      return { flexGrow: value, flexBasis: '0', width: (value / total * 100) + '%' };
    },
  },
};

const LevelTwo = {
  props: ['projectId', 'projects'],
  emits: ['back', 'jump'],
  data() {
    return {
      board: null,
      error: null,
      highlightedTaskId: null,
      detailTaskId: null,
      detail: null,
      detailLoading: false,
      detailError: null,
    };
  },
  computed: {
    otherBlockedProjects() {
      return (this.projects || []).filter(p => p.id !== this.projectId && p.hasBlocked);
    },
    totalCount() {
      if (!this.board) return 0;
      return STATUS_ORDER.reduce((sum, s) => sum + (this.board.columns[s] || []).length, 0);
    },
    doneCount() {
      return this.board ? (this.board.columns.DONE || []).length : 0;
    },
  },
  created() {
    this.loadBoard();
    this._keyHandler = (e) => {
      if (e.key === 'Escape') {
        if (this.detailTaskId !== null) this.closeDetail();
        else this.$emit('back');
      } else if (e.key === 'Tab' && this.detailTaskId !== null) {
        this.keepFocusInDrawer(e);
      }
    };
    window.addEventListener('keydown', this._keyHandler);
    this._popstateHandler = () => {
      if (this.detailTaskId !== null) this.dismissDetail();
    };
    window.addEventListener('popstate', this._popstateHandler);
    this._unsubscribe = window.__boardBus.on('task.status_changed', (payload) => {
      if (payload.projectId === this.projectId) {
        this.loadBoard();
        if (payload.taskId === this.detailTaskId) this.loadDetail(payload.taskId, true);
        this.highlightedTaskId = payload.taskId;
        setTimeout(() => { this.highlightedTaskId = null; }, 900);
      }
    });
    this._unsubscribeTasks = window.__boardBus.on('tasks.created', (payload) => {
      if (payload.projectId === this.projectId) {
        this.loadBoard();
        if (this.detailTaskId !== null) this.loadDetail(this.detailTaskId, true);
      }
    });
  },
  beforeUnmount() {
    window.removeEventListener('keydown', this._keyHandler);
    window.removeEventListener('popstate', this._popstateHandler);
    document.body.classList.remove('drawer-open');
    if (this._unsubscribe) this._unsubscribe();
    if (this._unsubscribeTasks) this._unsubscribeTasks();
  },
  watch: {
    projectId() { this.loadBoard(); },
  },
  methods: {
    async loadBoard() {
      try {
        this.board = await fetchJson(`/api/projects/${this.projectId}/board`);
        this.error = null;
      } catch (e) {
        this.error = e.message;
      }
    },
    column(status) {
      return this.board ? (this.board.columns[status] || []) : [];
    },
    async openDetail(taskId, event) {
      const switching = this.detailTaskId !== null;
      if (!switching) {
        this._detailOpener = event && event.currentTarget;
        window.history.pushState({ taskDrawer: true, taskId }, '');
      }
      this.detailTaskId = taskId;
      this.detail = null;
      this.detailError = null;
      document.body.classList.add('drawer-open');
      if (switching && window.history.state && window.history.state.taskDrawer) {
        window.history.replaceState({ taskDrawer: true, taskId }, '');
      }
      await nextTick();
      if (this.$refs.detailClose) this.$refs.detailClose.focus();
      this.loadDetail(taskId);
    },
    async loadDetail(taskId, preserveScroll = false) {
      if (taskId === null || taskId === undefined) return;
      const panel = this.$refs.detailPanel;
      const previousScroll = preserveScroll && panel ? panel.scrollTop : 0;
      if (!preserveScroll) this.detailLoading = true;
      this.detailError = null;
      try {
        const payload = await fetchJson(`/api/projects/${this.projectId}/tasks/${taskId}`);
        if (this.detailTaskId !== taskId) return;
        this.detail = payload;
        await nextTick();
        if (preserveScroll && this.$refs.detailPanel) this.$refs.detailPanel.scrollTop = previousScroll;
      } catch (e) {
        if (this.detailTaskId === taskId) this.detailError = e.message;
      } finally {
        if (this.detailTaskId === taskId) this.detailLoading = false;
      }
    },
    closeDetail() {
      if (window.history.state && window.history.state.taskDrawer) window.history.back();
      else this.dismissDetail();
    },
    dismissDetail() {
      const opener = this._detailOpener;
      this.detailTaskId = null;
      this.detail = null;
      this.detailError = null;
      this.detailLoading = false;
      document.body.classList.remove('drawer-open');
      nextTick(() => {
        if (opener && document.contains(opener)) opener.focus();
        this._detailOpener = null;
      });
    },
    keepFocusInDrawer(event) {
      const panel = this.$refs.detailPanel;
      if (!panel) return;
      const focusable = Array.from(panel.querySelectorAll(
        'button:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])'
      ));
      if (!focusable.length) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    },
    formatDate(value) {
      if (!value) return '—';
      const date = new Date(value);
      if (Number.isNaN(date.getTime())) return value;
      return new Intl.DateTimeFormat('zh-TW', {
        year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
      }).format(date);
    },
    statusChange(entry) {
      if (!entry.fromStatus) return `建立為 ${entry.toStatus}`;
      return `${entry.fromStatus} → ${entry.toStatus}`;
    },
  },
  template: `
    <div>
      <div class="board-header">
        <button class="back-btn" @click="$emit('back')">&larr; 總覽</button>
        <h2 class="board-title" v-if="board">{{ board.name }}</h2>
        <span class="board-progress" v-if="board">{{ doneCount }}/{{ totalCount }}</span>
        <button
          v-for="p in otherBlockedProjects"
          :key="p.id"
          class="blocked-elsewhere"
          @click="$emit('jump', p.id)"
        >⚠ {{ p.name }}</button>
      </div>
      <div v-if="error" class="empty-state">{{ error }}</div>
      <div v-else-if="board && totalCount === 0" class="empty-state">這個專案還沒有任務。</div>
      <div v-else-if="board" class="columns">
        <div v-for="status in ['TODO','IN_PROGRESS','BLOCKED','DONE']" :key="status">
          <div class="column-head">{{ status }}</div>
          <div class="column-count">{{ column(status).length }}</div>
          <transition-group name="task" tag="div" class="column-body">
            <button
              v-for="task in column(status)"
              :key="task.id"
              type="button"
              class="task-card"
              :class="['st-' + status, { highlight: task.id === highlightedTaskId }]"
              @click="openDetail(task.id, $event)"
              :aria-label="'查看任務 #' + task.id + '：' + task.title"
            >
              <div class="task-id">#{{ task.id }}</div>
              <div>{{ task.title }}</div>
              <div v-if="task.category" class="task-category">{{ task.category }}</div>
              <div
                v-if="task.assignee && ['IN_PROGRESS', 'BLOCKED'].includes(status)"
                class="task-assignee"
              >@{{ task.assignee }}</div>
              <div
                v-if="task.waitingFor && task.waitingFor.length"
                class="task-waiting"
                :title="'前置任務完成前不會被認領'"
              >等待 {{ task.waitingFor.map(id => '#' + id).join('、') }}</div>
            </button>
          </transition-group>
        </div>
      </div>

      <div
        v-if="detailTaskId !== null"
        class="detail-backdrop"
        @mousedown.self="closeDetail"
        aria-hidden="true"
      ></div>
      <aside
        v-if="detailTaskId !== null"
        ref="detailPanel"
        class="detail-drawer"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="'task-detail-title-' + detailTaskId"
      >
        <div class="detail-toolbar">
          <span class="detail-kicker">TASK #{{ detailTaskId }}</span>
          <button ref="detailClose" type="button" class="detail-close" @click="closeDetail" aria-label="關閉任務詳情">×</button>
        </div>

        <div v-if="detailLoading" class="detail-skeleton" aria-live="polite" aria-label="正在載入任務詳情">
          <span></span><span></span><span></span><span></span><span></span>
        </div>
        <div v-else-if="detailError" class="detail-error" role="alert">
          <p>{{ detailError }}</p>
          <button type="button" class="detail-retry" @click="loadDetail(detailTaskId)">重新載入</button>
        </div>
        <div v-else-if="detail" class="detail-content">
          <div class="detail-heading">
            <span class="detail-status" :class="'st-' + detail.status">{{ detail.status }}</span>
            <span class="detail-category">{{ detail.category }}</span>
          </div>
          <h2 :id="'task-detail-title-' + detail.id" class="detail-title">{{ detail.title }}</h2>

          <dl class="detail-meta">
            <div><dt>ASSIGNEE</dt><dd>{{ detail.assignee ? '@' + detail.assignee : '—' }}</dd></div>
            <div><dt>CLAIMED</dt><dd>{{ formatDate(detail.claimedAt) }}</dd></div>
            <div><dt>UPDATED</dt><dd>{{ formatDate(detail.updatedAt) }}</dd></div>
            <div><dt>SORT</dt><dd>{{ detail.sortOrder }}</dd></div>
          </dl>

          <section class="detail-section">
            <h3>說明</h3>
            <p v-if="detail.description" class="detail-description">{{ detail.description }}</p>
            <p v-else class="detail-empty">沒有說明</p>
          </section>

          <section v-if="detail.currentBlocker" class="detail-section detail-blocker">
            <h3>目前阻塞</h3>
            <p class="detail-description">{{ detail.currentBlocker.detail }}</p>
            <div class="detail-note">{{ detail.currentBlocker.reasonType }} · @{{ detail.currentBlocker.blockedBy }} · {{ formatDate(detail.currentBlocker.blockedAt) }}</div>
          </section>

          <section class="detail-section">
            <h3>前置任務 <span>{{ detail.prerequisites.length }}</span></h3>
            <div v-if="detail.prerequisites.length" class="related-list">
              <button v-for="item in detail.prerequisites" :key="item.id" type="button" @click="openDetail(item.id, $event)">
                <span>#{{ item.id }} · {{ item.title }}</span><small>{{ item.status }}</small>
              </button>
            </div>
            <p v-else class="detail-empty">無</p>
          </section>

          <section class="detail-section">
            <h3>下游任務 <span>{{ detail.downstreamTasks.length }}</span></h3>
            <div v-if="detail.downstreamTasks.length" class="related-list">
              <button v-for="item in detail.downstreamTasks" :key="item.id" type="button" @click="openDetail(item.id, $event)">
                <span>#{{ item.id }} · {{ item.title }}</span><small>{{ item.status }}</small>
              </button>
            </div>
            <p v-else class="detail-empty">無</p>
          </section>

          <section v-if="detail.completionEvidence" class="detail-section">
            <h3>完成證據</h3>
            <p class="detail-description">{{ detail.completionEvidence.summary }}</p>
            <div v-if="detail.completionEvidence.commitRef" class="detail-note">COMMIT {{ detail.completionEvidence.commitRef }}</div>
            <ul v-if="detail.completionEvidence.verifications.length" class="verification-list">
              <li v-for="item in detail.completionEvidence.verifications" :key="item.id">
                <strong>{{ item.name }}</strong><span>{{ item.result }}</span><p v-if="item.detail">{{ item.detail }}</p>
              </li>
            </ul>
          </section>

          <section class="detail-section">
            <h3>完整歷史 <span>{{ detail.history.length }}</span></h3>
            <ol v-if="detail.history.length" class="history-timeline">
              <li v-for="entry in detail.history" :key="entry.id">
                <time>{{ formatDate(entry.createdAt) }}</time>
                <strong>{{ statusChange(entry) }}</strong>
                <p v-if="entry.note">{{ entry.note }}</p>
              </li>
            </ol>
            <p v-else class="detail-empty">沒有歷史紀錄</p>
          </section>
        </div>
      </aside>
    </div>
  `,
};

function createEventBus() {
  const listeners = {};
  return {
    on(type, fn) {
      (listeners[type] = listeners[type] || []).push(fn);
      return () => {
        listeners[type] = (listeners[type] || []).filter(f => f !== fn);
      };
    },
    emit(type, payload) {
      (listeners[type] || []).forEach(fn => fn(payload));
    },
  };
}

window.__boardBus = createEventBus();

createApp({
  components: { LevelOne, LevelTwo },
  setup() {
    const currentProjectId = ref(null);
    const projects = ref([]);
    const sseConnected = ref(false);
    const railFlashClass = ref('');
    let railFlashTimer = null;
    let eventSource = null;

    async function loadProjects() {
      try {
        projects.value = await fetchJson('/api/projects');
      } catch (e) {
        console.error(e);
      }
    }

    function flashRail(statusLike) {
      const cls = RAIL_FLASH_CLASS[statusLike] || 'flash-progress';
      railFlashClass.value = cls + ' rail-flash';
      if (railFlashTimer) clearTimeout(railFlashTimer);
      railFlashTimer = setTimeout(() => { railFlashClass.value = ''; }, 600);
    }

    function updateProjectListForEvent(type, payload) {
      if (type === 'project.created' || type === 'tasks.created') {
        loadProjects();
        return;
      }
      if (type === 'task.status_changed') {
        // 更新記憶體中的 counts，讓 Level 2 返回 Level 1 時資料是即時的
        const project = projects.value.find(p => p.id === payload.projectId);
        if (project) {
          if (project.counts[payload.from] > 0) project.counts[payload.from] -= 1;
          project.counts[payload.to] = (project.counts[payload.to] || 0) + 1;
          project.hasBlocked = project.counts.BLOCKED > 0;
          project.updatedAt = payload.at;
        } else {
          loadProjects();
        }
      }
    }

    function connectSse() {
      eventSource = new EventSource('/api/events');

      eventSource.onopen = () => { sseConnected.value = true; };
      eventSource.onerror = () => {
        sseConnected.value = false;
        // 瀏覽器原生 EventSource 會自動重連；重連後直接重抓一次即可，不做事件重播
        eventSource.addEventListener('open', () => {
          loadProjects();
          if (currentProjectId.value !== null) {
            window.__boardBus.emit('tasks.created', { projectId: currentProjectId.value });
          }
        }, { once: true });
      };

      ['project.created', 'tasks.created', 'task.status_changed'].forEach((type) => {
        eventSource.addEventListener(type, (e) => {
          const payload = JSON.parse(e.data);
          flashRail(payload.to || 'IN_PROGRESS');
          updateProjectListForEvent(type, payload);
          window.__boardBus.emit(type, payload);
        });
      });

      eventSource.addEventListener('heartbeat', () => {
        sseConnected.value = true;
      });
    }

    onMounted(() => {
      loadProjects();
      connectSse();
    });

    onBeforeUnmount(() => {
      if (eventSource) eventSource.close();
    });

    return { currentProjectId, projects, sseConnected, railFlashClass };
  },
}).mount('#app');
