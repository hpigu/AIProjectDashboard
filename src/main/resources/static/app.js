const { createApp, ref, computed, onMounted, onBeforeUnmount, nextTick } = Vue;

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

async function fetchJson(url, options) {
  const res = await fetch(url, options);
  if (!res.ok) {
    throw new Error(`API 錯誤 ${res.status}：${url}`);
  }
  return res.json();
}

const LevelOne = {
  props: ['projects'],
  emits: ['select'],
  data() {
    const params = new URLSearchParams(window.location.search);
    return {
      filters: {
        query: params.get('projectQuery') || '',
        status: params.get('projectStatus') || 'ACTIVE',
        blocked: params.get('projectBlocked') === 'true',
        sort: params.get('projectSort') || 'UPDATED_DESC',
      },
      filteredProjects: [],
      loading: false,
      loaded: false,
      error: null,
      searchTimer: null,
      requestController: null,
    };
  },
  computed: {
    activeProjects() {
      return this.loaded ? this.filteredProjects : this.projects;
    },
    hasFilters() {
      return this.filters.query || this.filters.status !== 'ACTIVE' || this.filters.blocked || this.filters.sort !== 'UPDATED_DESC';
    },
  },
  created() {
    this.loadProjects();
    this._unsubscribe = window.__boardBus.on('project.created', () => this.loadProjects());
    this._unsubscribeTasks = window.__boardBus.on('tasks.created', () => this.loadProjects());
    this._unsubscribeStatus = window.__boardBus.on('task.status_changed', () => this.loadProjects());
  },
  beforeUnmount() {
    if (this.searchTimer) clearTimeout(this.searchTimer);
    if (this.requestController) this.requestController.abort();
    [this._unsubscribe, this._unsubscribeTasks, this._unsubscribeStatus].forEach(fn => fn && fn());
  },
  watch: {
    'filters.query'() { this.scheduleLoad(); },
    'filters.status'() { this.loadProjects(); },
    'filters.blocked'() { this.loadProjects(); },
    'filters.sort'() { this.loadProjects(); },
  },
  template: `
    <div>
      <section class="filter-panel project-filters" aria-label="專案篩選">
        <label class="filter-field filter-search"><span>專案名稱前綴</span>
          <input v-model="filters.query" type="search" placeholder="輸入專案名稱" autocomplete="off">
        </label>
        <div class="filter-field"><span>狀態</span>
          <div class="filter-choice" role="group" aria-label="專案狀態">
            <button v-for="status in ['ACTIVE', 'ARCHIVED', 'ALL']" :key="status" type="button"
              :class="{ selected: filters.status === status }" @click="filters.status = status">{{ status }}</button>
          </div>
        </div>
        <label class="filter-check"><input v-model="filters.blocked" type="checkbox"> 只看 BLOCKED</label>
        <label class="filter-field filter-sort"><span>排序</span>
          <select v-model="filters.sort"><option value="UPDATED_DESC">最近更新</option><option value="NAME">名稱</option><option value="PROGRESS">完成比例</option></select>
        </label>
        <button type="button" class="filter-clear" @click="clearFilters" :disabled="!hasFilters">清除條件</button>
      </section>
      <div v-if="error" class="empty-state">{{ error }}</div>
      <div v-else-if="!loading && activeProjects.length === 0" class="empty-state">
        還沒有符合條件的專案。
      </div>
      <div v-else>
        <button
          v-for="p in activeProjects"
          :key="p.id"
          class="project-row"
          :class="{ blocked: p.hasBlocked }"
          @click="$emit('select', p)"
        >
          <div class="project-row-top">
            <div class="project-name">
              {{ p.name }}
              <span v-if="p.hasBlocked" class="blocked-badge">⚠ 阻塞</span>
              <span v-if="p.status === 'ARCHIVED'" class="archive-badge">ARCHIVED</span>
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
    scheduleLoad() {
      this.syncFiltersToUrl();
      if (this.searchTimer) clearTimeout(this.searchTimer);
      this.searchTimer = setTimeout(() => this.loadProjects(), 250);
    },
    syncFiltersToUrl() {
      const url = new URL(window.location.href);
      const values = {
        projectQuery: this.filters.query,
        projectStatus: this.filters.status === 'ACTIVE' ? '' : this.filters.status,
        projectBlocked: this.filters.blocked ? 'true' : '',
        projectSort: this.filters.sort === 'UPDATED_DESC' ? '' : this.filters.sort,
      };
      Object.entries(values).forEach(([key, value]) => value ? url.searchParams.set(key, value) : url.searchParams.delete(key));
      window.history.replaceState(window.history.state, '', url);
    },
    async loadProjects() {
      this.syncFiltersToUrl();
      if (this.requestController) this.requestController.abort();
      const controller = new AbortController();
      this.requestController = controller;
      this.loading = true;
      this.error = null;
      const params = new URLSearchParams({ status: this.filters.status, sort: this.filters.sort });
      if (this.filters.query) params.set('q', this.filters.query);
      if (this.filters.blocked) params.set('blocked', 'true');
      try {
        this.filteredProjects = await fetchJson('/api/projects?' + params.toString(), { signal: controller.signal });
        this.loaded = true;
      } catch (e) {
        if (e.name !== 'AbortError') this.error = e.message;
      } finally {
        if (!controller.signal.aborted) this.loading = false;
      }
    },
    clearFilters() {
      this.filters = { query: '', status: 'ACTIVE', blocked: false, sort: 'UPDATED_DESC' };
    },
    relTime(iso) { return relativeTime(iso); },
    segStyle(p, key) {
      const total = p.total || 1;
      const value = p.counts[key] || 0;
      return { flexGrow: value, flexBasis: '0', width: (value / total * 100) + '%' };
    },
  },
};

const LevelTwo = {
  props: ['projectId', 'projects', 'projectSummary'],
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
      filters: {
        query: new URLSearchParams(window.location.search).get('taskQuery') || '',
        category: new URLSearchParams(window.location.search).get('taskCategory') || '',
        assignee: new URLSearchParams(window.location.search).get('taskAssignee') || '',
        waiting: new URLSearchParams(window.location.search).get('taskWaiting') === 'true',
        claimable: new URLSearchParams(window.location.search).get('taskClaimable') === 'true',
      },
      appliedQuery: new URLSearchParams(window.location.search).get('taskQuery') || '',
      searchTimer: null,
      advancedOpen: window.matchMedia('(min-width: 701px)').matches,
      boardController: null,
      viewMode: new URLSearchParams(window.location.search).get('view') === 'dependencies' ? 'dependencies' : 'kanban',
      graph: null,
      graphError: null,
      graphLoading: false,
      graphController: null,
      graphExpanded: false,
      graphHiddenCategories: {},
      graphVertical: window.matchMedia('(max-width: 700px)').matches,
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
    categories() {
      return [...new Set(this.allTasks.map(task => task.category).filter(Boolean))].sort();
    },
    assignees() {
      return [...new Set(this.allTasks.map(task => task.assignee).filter(Boolean))].sort((a, b) => a.localeCompare(b));
    },
    allTasks() {
      if (!this.board) return [];
      return STATUS_ORDER.flatMap(status => (this.board.columns[status] || []).map(task => ({ ...task, status })));
    },
    hasTaskFilters() {
      return this.filters.query || this.filters.category || this.filters.assignee || this.filters.waiting || this.filters.claimable;
    },
    graphLayout() {
      if (!this.graph) return null;
      const allNodes = this.graph.nodes || [];
      const nodesById = new Map(allNodes.map(node => [node.id, node]));
      const allEdges = (this.graph.edges || []).filter(edge =>
        nodesById.has(edge.prerequisiteTaskId) && nodesById.has(edge.taskId));
      const prerequisites = new Map(allNodes.map(node => [node.id, []]));
      const downstream = new Map(allNodes.map(node => [node.id, []]));
      allEdges.forEach(edge => {
        prerequisites.get(edge.taskId).push(edge.prerequisiteTaskId);
        downstream.get(edge.prerequisiteTaskId).push(edge.taskId);
      });

      const matches = (node) => {
        const query = this.appliedQuery.trim().toLocaleLowerCase();
        const titleMatches = !query || node.title.toLocaleLowerCase().startsWith(query);
        const categoryMatches = !this.filters.category || node.category === this.filters.category;
        const assigneeMatches = !this.filters.assignee
          || (this.filters.assignee === '__unassigned__' ? !node.assignee : node.assignee === this.filters.assignee);
        const waiting = prerequisites.get(node.id).some(id => nodesById.get(id).status !== 'DONE');
        return titleMatches && categoryMatches && assigneeMatches
          && (!this.filters.waiting || waiting) && (!this.filters.claimable || node.claimable);
      };
      const categoryVisible = (node) => !this.graphHiddenCategories[node.category];
      const filteredNodes = allNodes.filter(node => matches(node) && categoryVisible(node));
      let selectedIds = new Set(filteredNodes.map(node => node.id));

      // Large boards start with work that can still move, plus its immediate context.
      // This preserves useful local relationships without turning the first view into a wall of nodes.
      if (!this.graphExpanded && filteredNodes.length > 24) {
        const activeNodes = filteredNodes.filter(node => node.status !== 'DONE' || node.claimable);
        const priorities = activeNodes.length ? activeNodes : filteredNodes;
        const localIds = new Set(priorities.slice(0, 16).map(node => node.id));
        priorities.slice(0, 16).forEach(node => {
          prerequisites.get(node.id).forEach(id => localIds.add(id));
          downstream.get(node.id).forEach(id => localIds.add(id));
        });
        selectedIds = new Set([...localIds].filter(id => {
          const node = nodesById.get(id);
          return node && matches(node) && categoryVisible(node);
        }).slice(0, 24));
      }

      const nodes = filteredNodes.filter(node => selectedIds.has(node.id));
      const edges = allEdges.filter(edge => selectedIds.has(edge.prerequisiteTaskId) && selectedIds.has(edge.taskId));
      const incoming = new Map(nodes.map(node => [node.id, 0]));
      const outgoing = new Map(nodes.map(node => [node.id, []]));
      edges.forEach(edge => {
        incoming.set(edge.taskId, incoming.get(edge.taskId) + 1);
        outgoing.get(edge.prerequisiteTaskId).push(edge.taskId);
      });
      const order = new Map(nodes.map((node, index) => [node.id, index]));
      const queue = nodes.filter(node => incoming.get(node.id) === 0).sort((a, b) => order.get(a.id) - order.get(b.id));
      const layerById = new Map(nodes.map(node => [node.id, 0]));
      const parentById = new Map();
      const distanceById = new Map(nodes.map(node => [node.id, 1]));
      for (let index = 0; index < queue.length; index += 1) {
        const current = queue[index];
        outgoing.get(current.id).forEach(nextId => {
          const nextLayer = layerById.get(current.id) + 1;
          if (nextLayer > layerById.get(nextId)) layerById.set(nextId, nextLayer);
          const nextDistance = distanceById.get(current.id) + 1;
          if (nextDistance > distanceById.get(nextId)) {
            distanceById.set(nextId, nextDistance);
            parentById.set(nextId, current.id);
          }
          incoming.set(nextId, incoming.get(nextId) - 1);
          if (incoming.get(nextId) === 0) queue.push(nodesById.get(nextId));
        });
      }

      const layers = [];
      nodes.forEach(node => {
        const layer = layerById.get(node.id) || 0;
        if (!layers[layer]) layers[layer] = [];
        layers[layer].push(node);
      });
      const maxRows = Math.max(1, ...layers.map(layer => (layer || []).length));
      const maxLayer = Math.max(0, layers.length - 1);
      const positioned = [];
      layers.forEach((layer, layerIndex) => (layer || []).forEach((node, rowIndex) => {
        positioned.push({ ...node, layer: layerIndex, row: rowIndex,
          waiting: prerequisites.get(node.id).some(id => nodesById.get(id).status !== 'DONE') });
      }));
      const longestEnd = positioned.length ? positioned.reduce((best, node) =>
        (distanceById.get(node.id) || 1) > (distanceById.get(best.id) || 1) ? node : best, positioned[0]) : null;
      const longestPath = [];
      let pathId = longestEnd && longestEnd.id;
      while (pathId !== undefined && pathId !== null) {
        longestPath.unshift(pathId);
        pathId = parentById.get(pathId);
      }
      const positionById = new Map(positioned.map(node => [node.id, node]));
      return {
        nodes: positioned,
        edges,
        layers: Math.max(1, maxLayer + 1),
        maxRows,
        longestPath,
        longestLength: longestPath.length,
        partial: !this.graphExpanded && filteredNodes.length > nodes.length,
        totalFiltered: filteredNodes.length,
        positionById,
      };
    },
  },
  created() {
    this.loadBoard();
    if (this.viewMode === 'dependencies') this.loadGraph();
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
        if (this.graph || this.viewMode === 'dependencies') this.loadGraph();
        if (payload.taskId === this.detailTaskId) this.loadDetail(payload.taskId, true);
        this.highlightedTaskId = payload.taskId;
        setTimeout(() => { this.highlightedTaskId = null; }, 900);
      }
    });
    this._unsubscribeTasks = window.__boardBus.on('tasks.created', (payload) => {
      if (payload.projectId === this.projectId) {
        this.loadBoard();
        if (this.graph || this.viewMode === 'dependencies') this.loadGraph();
        if (this.detailTaskId !== null) this.loadDetail(this.detailTaskId, true);
      }
    });
    this._resizeHandler = () => {
      if (window.matchMedia('(min-width: 701px)').matches) this.advancedOpen = true;
      this.graphVertical = window.matchMedia('(max-width: 700px)').matches;
    };
    window.addEventListener('resize', this._resizeHandler);
  },
  beforeUnmount() {
    window.removeEventListener('keydown', this._keyHandler);
    window.removeEventListener('popstate', this._popstateHandler);
    window.removeEventListener('resize', this._resizeHandler);
    if (this.searchTimer) clearTimeout(this.searchTimer);
    if (this.boardController) this.boardController.abort();
    if (this.graphController) this.graphController.abort();
    document.body.classList.remove('drawer-open');
    if (this._unsubscribe) this._unsubscribe();
    if (this._unsubscribeTasks) this._unsubscribeTasks();
  },
  watch: {
    projectId() {
      this.graph = null;
      this.graphError = null;
      this.graphExpanded = false;
      this.graphHiddenCategories = {};
      this.loadBoard();
      if (this.viewMode === 'dependencies') this.loadGraph();
    },
    'filters.query'() {
      this.syncFiltersToUrl();
      if (this.searchTimer) clearTimeout(this.searchTimer);
      this.searchTimer = setTimeout(() => { this.appliedQuery = this.filters.query; }, 250);
    },
    'filters.category'() { this.syncFiltersToUrl(); },
    'filters.assignee'() { this.syncFiltersToUrl(); },
    'filters.waiting'() { this.syncFiltersToUrl(); },
    'filters.claimable'() { this.syncFiltersToUrl(); },
  },
  methods: {
    async loadBoard() {
      if (this.boardController) this.boardController.abort();
      const controller = new AbortController();
      this.boardController = controller;
      try {
        this.board = await fetchJson(`/api/projects/${this.projectId}/board`, { signal: controller.signal });
        this.error = null;
      } catch (e) {
        if (e.name !== 'AbortError') this.error = e.message;
      }
    },
    async loadGraph() {
      if (this.graphController) this.graphController.abort();
      const controller = new AbortController();
      this.graphController = controller;
      this.graphLoading = true;
      this.graphError = null;
      try {
        this.graph = await fetchJson(`/api/projects/${this.projectId}/dependencies`, { signal: controller.signal });
      } catch (e) {
        if (e.name !== 'AbortError') this.graphError = e.message;
      } finally {
        if (!controller.signal.aborted) this.graphLoading = false;
      }
    },
    setViewMode(mode) {
      this.viewMode = mode;
      const url = new URL(window.location.href);
      if (mode === 'dependencies') url.searchParams.set('view', 'dependencies');
      else url.searchParams.delete('view');
      window.history.replaceState(window.history.state, '', url);
      if (mode === 'dependencies' && !this.graph && !this.graphLoading) this.loadGraph();
    },
    toggleGraphCategory(category) {
      this.graphHiddenCategories = {
        ...this.graphHiddenCategories,
        [category]: !this.graphHiddenCategories[category],
      };
    },
    showAllGraphCategories() {
      this.graphHiddenCategories = {};
    },
    graphNodeStyle(node) {
      return this.graphVertical
        ? { gridColumn: node.row + 1, gridRow: node.layer + 1 }
        : { gridColumn: node.layer + 1, gridRow: node.row + 1 };
    },
    graphEdgePath(edge) {
      const layout = this.graphLayout;
      if (!layout) return '';
      const from = layout.positionById.get(edge.prerequisiteTaskId);
      const to = layout.positionById.get(edge.taskId);
      if (!from || !to) return '';
      const horizontal = !this.graphVertical;
      const fromX = horizontal ? (from.layer + .5) * 100 : (from.row + .5) * 100;
      const fromY = horizontal ? (from.row + .5) * 100 : (from.layer + .5) * 100;
      const toX = horizontal ? (to.layer + .5) * 100 : (to.row + .5) * 100;
      const toY = horizontal ? (to.row + .5) * 100 : (to.layer + .5) * 100;
      return horizontal
        ? `M ${fromX} ${fromY} C ${(fromX + toX) / 2} ${fromY}, ${(fromX + toX) / 2} ${toY}, ${toX} ${toY}`
        : `M ${fromX} ${fromY} C ${fromX} ${(fromY + toY) / 2}, ${toX} ${(fromY + toY) / 2}, ${toX} ${toY}`;
    },
    graphViewBox() {
      const layout = this.graphLayout;
      if (!layout) return '0 0 100 100';
      const width = this.graphVertical ? layout.maxRows * 100 : layout.layers * 100;
      const height = this.graphVertical ? layout.layers * 100 : layout.maxRows * 100;
      return `0 0 ${width} ${height}`;
    },
    graphCanvasStyle() {
      const layout = this.graphLayout;
      if (!layout) return {};
      return this.graphVertical
        ? { gridTemplateColumns: `repeat(${layout.maxRows}, minmax(0, 1fr))`, gridTemplateRows: `repeat(${layout.layers}, 144px)` }
        : { gridTemplateColumns: `repeat(${layout.layers}, minmax(220px, 1fr))`, gridTemplateRows: `repeat(${layout.maxRows}, 144px)` };
    },
    graphWaitingIds(node) {
      if (!this.graph) return [];
      const nodesById = new Map(this.graph.nodes.map(item => [item.id, item]));
      return this.graph.edges
        .filter(edge => edge.taskId === node.id && nodesById.get(edge.prerequisiteTaskId).status !== 'DONE')
        .map(edge => edge.prerequisiteTaskId);
    },
    column(status) {
      return this.allTasks.filter(task => task.status === status && this.matchesTaskFilters(task));
    },
    columnTotal(status) {
      return this.board ? (this.board.columns[status] || []).length : 0;
    },
    matchesTaskFilters(task) {
      const query = this.appliedQuery.trim().toLocaleLowerCase();
      const titleMatches = !query || task.title.toLocaleLowerCase().startsWith(query);
      const categoryMatches = !this.filters.category || task.category === this.filters.category;
      const assigneeMatches = !this.filters.assignee
        || (this.filters.assignee === '__unassigned__' ? !task.assignee : task.assignee === this.filters.assignee);
      const waiting = task.waitingFor && task.waitingFor.length > 0;
      const claimable = task.status === 'TODO' && !waiting;
      return titleMatches && categoryMatches && assigneeMatches
        && (!this.filters.waiting || waiting) && (!this.filters.claimable || claimable);
    },
    syncFiltersToUrl() {
      const url = new URL(window.location.href);
      const values = {
        taskQuery: this.filters.query,
        taskCategory: this.filters.category,
        taskAssignee: this.filters.assignee,
        taskWaiting: this.filters.waiting ? 'true' : '',
        taskClaimable: this.filters.claimable ? 'true' : '',
      };
      Object.entries(values).forEach(([key, value]) => value ? url.searchParams.set(key, value) : url.searchParams.delete(key));
      window.history.replaceState(window.history.state, '', url);
    },
    clearTaskFilters() {
      this.filters = { query: '', category: '', assignee: '', waiting: false, claimable: false };
      this.appliedQuery = '';
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
    formatDuration(from, to) {
      if (!from) return '—';
      const start = new Date(from).getTime();
      const end = to ? new Date(to).getTime() : Date.now();
      if (Number.isNaN(start) || Number.isNaN(end) || end < start) return '—';
      const minutes = Math.floor((end - start) / 60000);
      if (minutes < 1) return '不到 1 分鐘';
      if (minutes < 60) return `${minutes} 分鐘`;
      const hours = Math.floor(minutes / 60);
      if (hours < 24) return `${hours} 小時 ${minutes % 60} 分鐘`;
      const days = Math.floor(hours / 24);
      return `${days} 天 ${hours % 24} 小時`;
    },
    blockerReasonLabel(reason) {
      return ({
        DEPENDENCY: '等待任務',
        USER_INPUT: '等待使用者',
        TECHNICAL: '技術問題',
        EXTERNAL: '外部因素',
        OTHER: '其他原因',
      })[reason] || reason || '未分類';
    },
    visibleVerifications(evidence) {
      const items = evidence && Array.isArray(evidence.verifications)
        ? evidence.verifications
        : [];
      return this.detail && this.detail.status === 'DONE'
        ? items.filter(item => item.result !== 'FAILED')
        : items;
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
        <span v-if="projectSummary && projectSummary.status === 'ARCHIVED'" class="archive-badge">ARCHIVED · 唯讀</span>
        <span class="board-progress" v-if="board">{{ doneCount }}/{{ totalCount }}</span>
        <button
          v-for="p in otherBlockedProjects"
          :key="p.id"
          class="blocked-elsewhere"
          @click="$emit('jump', p.id)"
        >⚠ {{ p.name }}</button>
      </div>
      <div v-if="board" class="view-switch" role="tablist" aria-label="專案檢視方式">
        <button type="button" role="tab" :aria-selected="viewMode === 'kanban'" :class="{ selected: viewMode === 'kanban' }" @click="setViewMode('kanban')">看板</button>
        <button type="button" role="tab" :aria-selected="viewMode === 'dependencies'" :class="{ selected: viewMode === 'dependencies' }" @click="setViewMode('dependencies')">相依圖</button>
      </div>
      <div v-if="error" class="empty-state">{{ error }}</div>
      <div v-else-if="board && totalCount === 0" class="empty-state">這個專案還沒有任務。</div>
      <div v-else-if="board && viewMode === 'kanban'" class="columns">
        <section class="filter-panel task-filters" aria-label="任務篩選">
          <label class="filter-field filter-search"><span>任務標題前綴</span>
            <input v-model="filters.query" type="search" placeholder="輸入任務標題" autocomplete="off">
          </label>
          <label class="filter-field"><span>主要類別</span>
            <select v-model="filters.category"><option value="">所有類別</option><option v-for="category in categories" :key="category" :value="category">{{ category }}</option></select>
          </label>
          <button type="button" class="advanced-trigger" @click="advancedOpen = true">進階條件</button>
          <button type="button" class="filter-clear" @click="clearTaskFilters" :disabled="!hasTaskFilters">清除條件</button>
        </section>
        <div v-if="advancedOpen" class="filter-sheet-backdrop" @click="advancedOpen = false"></div>
        <section v-if="advancedOpen" class="filter-advanced" aria-label="進階任務篩選">
          <div class="filter-sheet-head"><strong>進階條件</strong><button type="button" @click="advancedOpen = false" aria-label="關閉進階條件">×</button></div>
          <label class="filter-field"><span>負責人</span>
            <select v-model="filters.assignee"><option value="">所有負責人</option><option value="__unassigned__">未指派</option><option v-for="assignee in assignees" :key="assignee" :value="assignee">@{{ assignee }}</option></select>
          </label>
          <label class="filter-check"><input v-model="filters.waiting" type="checkbox"> 只看等待前置</label>
          <label class="filter-check"><input v-model="filters.claimable" type="checkbox"> 只看可認領</label>
          <p class="filter-help">可認領 = TODO，且所有前置任務已完成。</p>
        </section>
        <div v-for="status in ['TODO','IN_PROGRESS','BLOCKED','DONE']" :key="status">
          <div class="column-head">{{ status }}</div>
          <div class="column-count"><span>{{ column(status).length }}</span><small>/ {{ columnTotal(status) }}</small></div>
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
      <section v-else-if="board && viewMode === 'dependencies'" class="dependency-view" aria-label="任務相依圖">
        <div class="dependency-toolbar">
          <div>
            <h3>相依關係</h3>
            <p>箭頭由前置任務指向下游任務。</p>
          </div>
          <button v-if="graphLayout && graphLayout.partial" type="button" class="graph-expand" @click="graphExpanded = true">展開完整圖（{{ graphLayout.totalFiltered }}）</button>
          <button v-else-if="graphExpanded" type="button" class="graph-expand" @click="graphExpanded = false">顯示局部關係</button>
        </div>
        <div v-if="graphLoading && !graph" class="empty-state">正在載入相依圖…</div>
        <div v-else-if="graphError" class="graph-error" role="alert"><p>{{ graphError }}</p><button type="button" class="graph-expand" @click="loadGraph">重新載入</button></div>
        <template v-else-if="graph && graphLayout">
          <div class="graph-category-filter" aria-label="依類別顯示或隱藏">
            <span>類別</span>
            <button v-for="category in categories" :key="category" type="button" :aria-pressed="!graphHiddenCategories[category]" :class="{ selected: !graphHiddenCategories[category] }" @click="toggleGraphCategory(category)">{{ category }}</button>
            <button v-if="Object.keys(graphHiddenCategories).some(category => graphHiddenCategories[category])" type="button" class="graph-reset" @click="showAllGraphCategories">全部顯示</button>
          </div>
          <p v-if="graphLayout.longestLength > 1" class="graph-chain">最長相依鏈：{{ graphLayout.longestPath.map(id => '#' + id).join(' → ') }}</p>
          <p v-if="graphLayout.partial" class="graph-scope">目前顯示可進行工作及其直接關係；可展開完整圖。</p>
          <p v-if="graph.edges.length === 0" class="graph-empty">尚未設定任務相依；以下是所有獨立任務。</p>
          <p v-if="graphLayout.nodes.length === 0" class="graph-empty">目前的篩選條件或類別設定沒有可顯示的任務。</p>
          <div v-else class="dependency-scroll">
            <div class="dependency-canvas" :class="{ vertical: graphVertical }" :style="graphCanvasStyle()">
              <svg class="dependency-edges" :viewBox="graphViewBox()" preserveAspectRatio="none" aria-hidden="true">
                <defs><marker id="dependency-arrow" markerWidth="7" markerHeight="7" refX="6" refY="3.5" orient="auto"><path d="M0,0 L7,3.5 L0,7 Z"></path></marker></defs>
                <path v-for="edge in graphLayout.edges" :key="edge.prerequisiteTaskId + '-' + edge.taskId" :d="graphEdgePath(edge)" marker-end="url(#dependency-arrow)"></path>
              </svg>
              <button
                v-for="node in graphLayout.nodes"
                :key="node.id"
                type="button"
                class="dependency-node"
                :class="['st-' + node.status, { claimable: node.claimable, waiting: node.waiting, 'on-longest-chain': graphLayout.longestPath.includes(node.id) }]"
                :style="graphNodeStyle(node)"
                @click="openDetail(node.id, $event)"
                :aria-label="'查看任務 #' + node.id + '：' + node.title"
              >
                <span class="dependency-id">#{{ node.id }}</span>
                <span class="dependency-title">{{ node.title }}</span>
                <span class="dependency-meta">{{ node.category || '未分類' }} · {{ node.status }}</span>
                <span v-if="node.claimable" class="dependency-badge claimable-badge">可認領</span>
                <span v-else-if="node.waiting" class="dependency-badge waiting-badge">等待 {{ graphWaitingIds(node).map(id => '#' + id).join('、') }}</span>
                <span v-if="graphLayout.longestPath.includes(node.id)" class="dependency-badge longest-badge">最長鏈</span>
              </button>
            </div>
          </div>
          <div class="graph-legend" aria-label="相依圖圖例"><span class="claimable-badge">可認領</span><span class="waiting-badge">等待前置</span><span class="longest-badge">最長鏈</span></div>
        </template>
      </section>

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
            <div class="section-title-row">
              <h3>目前阻塞</h3>
              <span class="reason-badge">{{ blockerReasonLabel(detail.currentBlocker.reasonType) }}</span>
            </div>
            <p class="detail-description">{{ detail.currentBlocker.detail }}</p>
            <dl class="blocker-meta">
              <div><dt>BLOCKED BY</dt><dd>{{ detail.currentBlocker.blockedBy ? '@' + detail.currentBlocker.blockedBy : '—' }}</dd></div>
              <div><dt>BLOCKED AT</dt><dd>{{ formatDate(detail.currentBlocker.blockedAt) }}</dd></div>
              <div><dt>DURATION</dt><dd>{{ formatDuration(detail.currentBlocker.blockedAt, detail.currentBlocker.clearedAt) }}</dd></div>
            </dl>
            <div v-if="detail.currentBlocker.blockingTasks.length" class="blocker-dependencies">
              <h4>BLOCKING TASKS</h4>
              <div class="related-list">
                <button v-for="item in detail.currentBlocker.blockingTasks" :key="item.id" type="button" @click="openDetail(item.id, $event)">
                  <span>#{{ item.id }} · {{ item.title }}</span><small :class="'st-text-' + item.status">{{ item.status }}</small>
                </button>
              </div>
            </div>
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

          <section v-if="detail.completionEvidence" class="detail-section detail-evidence">
            <div class="section-title-row">
              <h3>{{ detail.status === 'DONE' ? '完成證據' : '最近一次完成證據' }}</h3>
              <span class="evidence-badge" :class="detail.status === 'DONE' ? 'is-current' : 'is-historical'">
                {{ detail.status === 'DONE' ? 'CURRENT' : 'HISTORICAL' }}
              </span>
            </div>
            <p class="detail-description">{{ detail.completionEvidence.summary }}</p>
            <dl class="evidence-meta">
              <div><dt>COMPLETED BY</dt><dd>{{ detail.completionEvidence.completedBy ? '@' + detail.completionEvidence.completedBy : '—' }}</dd></div>
              <div><dt>COMPLETED AT</dt><dd>{{ formatDate(detail.completionEvidence.completedAt) }}</dd></div>
            </dl>
            <div v-if="detail.completionEvidence.changedFiles" class="evidence-field">
              <h4>CHANGED FILES</h4>
              <pre>{{ detail.completionEvidence.changedFiles }}</pre>
            </div>
            <div v-if="detail.completionEvidence.commitRef" class="evidence-field">
              <h4>COMMIT / PR</h4>
              <div class="code-value">{{ detail.completionEvidence.commitRef }}</div>
            </div>
            <ul v-if="visibleVerifications(detail.completionEvidence).length" class="verification-list">
              <li v-for="item in visibleVerifications(detail.completionEvidence)" :key="item.id" :class="'verification-' + item.result">
                <div class="verification-heading">
                  <strong>{{ item.name }}</strong><span class="verification-badge">{{ item.result }}</span>
                </div>
                <pre v-if="item.detail">{{ item.detail }}</pre>
              </li>
            </ul>
          </section>

          <section class="detail-section">
            <h3>完整歷史 <span>{{ detail.history.length }}</span></h3>
            <ol v-if="detail.history.length" class="history-timeline">
              <li v-for="entry in detail.history" :key="entry.id" :class="'history-' + entry.toStatus">
                <time>{{ formatDate(entry.createdAt) }}</time>
                <div class="history-heading">
                  <strong>{{ statusChange(entry) }}</strong>
                  <span v-if="entry.toStatus === 'BLOCKED'" class="history-kind is-blocker">BLOCKER</span>
                  <span v-else-if="entry.toStatus === 'DONE'" class="history-kind is-evidence">EVIDENCE</span>
                </div>
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
    const initialProject = Number(new URLSearchParams(window.location.search).get('project'));
    const currentProjectId = ref(Number.isSafeInteger(initialProject) && initialProject > 0 ? initialProject : null);
    const projects = ref([]);
    const sseConnected = ref(false);
    const railFlashClass = ref('');
    let railFlashTimer = null;
    let eventSource = null;

    async function loadProjects() {
      try {
        projects.value = await fetchJson('/api/projects?status=ALL&sort=UPDATED_DESC');
      } catch (e) {
        console.error(e);
      }
    }

    const selectedProject = computed(() => projects.value.find(project => project.id === currentProjectId.value) || null);

    function selectProject(project) {
      const id = typeof project === 'object' ? project.id : project;
      if (id === currentProjectId.value) return;
      const url = new URL(window.location.href);
      url.searchParams.set('project', id);
      window.history.pushState({ projectId: id }, '', url);
      currentProjectId.value = id;
    }

    function showProjectList() {
      const url = new URL(window.location.href);
      url.searchParams.delete('project');
      window.history.pushState({ projectId: null }, '', url);
      currentProjectId.value = null;
    }

    function syncProjectFromUrl() {
      const id = Number(new URLSearchParams(window.location.search).get('project'));
      currentProjectId.value = Number.isSafeInteger(id) && id > 0 ? id : null;
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
      window.addEventListener('popstate', syncProjectFromUrl);
    });

    onBeforeUnmount(() => {
      if (eventSource) eventSource.close();
      window.removeEventListener('popstate', syncProjectFromUrl);
    });

    return { currentProjectId, projects, selectedProject, selectProject, showProjectList, sseConnected, railFlashClass };
  },
}).mount('#app');
