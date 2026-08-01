const { createApp, ref, reactive, computed, onMounted, onBeforeUnmount } = Vue;

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
      if (e.key === 'Escape') this.$emit('back');
    };
    window.addEventListener('keydown', this._keyHandler);
    this._unsubscribe = window.__boardBus.on('task.status_changed', (payload) => {
      if (payload.projectId === this.projectId) {
        this.loadBoard();
        this.highlightedTaskId = payload.taskId;
        setTimeout(() => { this.highlightedTaskId = null; }, 900);
      }
    });
    this._unsubscribeTasks = window.__boardBus.on('tasks.created', (payload) => {
      if (payload.projectId === this.projectId) this.loadBoard();
    });
  },
  beforeUnmount() {
    window.removeEventListener('keydown', this._keyHandler);
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
            <div
              v-for="task in column(status)"
              :key="task.id"
              class="task-card"
              :class="['st-' + status, { highlight: task.id === highlightedTaskId }]"
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
            </div>
          </transition-group>
        </div>
      </div>
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
