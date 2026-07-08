<template>
  <div class="chatbox">
    <div class="chat-header">
      <span class="header-title">AI 데이터 분석</span>
      <span class="header-badge">Claude AI</span>
    </div>

    <div class="messages" ref="msgEl">
      <div v-for="(m, i) in messages" :key="i" :class="['msg', m.role]">

        <!-- 사용자 메시지: plain text -->
        <div v-if="m.role === 'user'" class="bubble user-bubble">
          {{ m.text }}
        </div>

        <!-- AI 메시지: 마크다운 렌더링 -->
        <div v-else class="bubble assistant-bubble">
          <div class="md-body" v-html="renderMd(m.text)"></div>
        </div>

      </div>

      <!-- 로딩 인디케이터 -->
      <div v-if="thinking" class="msg assistant">
        <div class="bubble assistant-bubble thinking-bubble">
          <span class="dot"></span>
          <span class="dot"></span>
          <span class="dot"></span>
        </div>
      </div>
    </div>

    <div class="input-row">
      <input
        v-model="input"
        @keydown.enter="send"
        placeholder="질문을 입력하세요  예) 수익이 가장 높은 고객은?"
        :disabled="thinking"
      />
      <button @click="send" :disabled="thinking || !input.trim()">
        {{ thinking ? '분석중' : '전송' }}
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick } from 'vue'
import { marked } from 'marked'

const props = defineProps({ sendChat: Function })

// marked 옵션: 표·코드블록·줄바꿈 모두 처리
marked.setOptions({ breaks: true, gfm: true })

function renderMd(text) {
  if (!text) return ''
  return marked.parse(text)
}

const input    = ref('')
const thinking = ref(false)
const msgEl    = ref(null)
const messages = ref([
  {
    role: 'assistant',
    text: '반도체 파운드리 데이터에 대해 자유롭게 질문하세요.\n\n**예시 질문**\n- 수익이 가장 높은 고객 Top 3는?\n- 최근 CRITICAL 불량이 발생한 Lot은?\n- 제품별 평균 수율을 알려줘'
  }
])

async function send() {
  const q = input.value.trim()
  if (!q || thinking.value) return
  messages.value.push({ role: 'user', text: q })
  input.value = ''
  thinking.value = true
  await nextTick()
  scroll()
  try {
    const answer = await props.sendChat(q)
    messages.value.push({ role: 'assistant', text: answer })
  } catch {
    messages.value.push({ role: 'assistant', text: '**오류**가 발생했습니다. 잠시 후 다시 시도해주세요.' })
  } finally {
    thinking.value = false
    await nextTick()
    scroll()
  }
}

function scroll() {
  if (msgEl.value) msgEl.value.scrollTop = msgEl.value.scrollHeight
}
</script>

<style scoped>
/* ── 전체 레이아웃 ───────────────────────────────────────── */
.chatbox {
  display: flex;
  flex-direction: column;
  height: 480px;
  border: 1px solid #e0e0e0;
  border-radius: 12px;
  overflow: hidden;
  background: #fff;
  box-shadow: 0 2px 12px rgba(0,0,0,.08);
}

/* ── 헤더 ───────────────────────────────────────────────── */
.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #1a1a2e;
  color: #fff;
  padding: 12px 16px;
}
.header-title { font-weight: 700; font-size: 14px; }
.header-badge {
  font-size: 10px;
  background: rgba(255,255,255,.15);
  border: 1px solid rgba(255,255,255,.25);
  border-radius: 20px;
  padding: 2px 10px;
  letter-spacing: .4px;
}

/* ── 메시지 목록 ─────────────────────────────────────────── */
.messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px 14px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  background: #f8f9fb;
}

.msg { display: flex; }
.msg.user { justify-content: flex-end; }
.msg.assistant { justify-content: flex-start; }

/* ── 버블 공통 ───────────────────────────────────────────── */
.bubble {
  max-width: 80%;
  padding: 10px 14px;
  border-radius: 14px;
  font-size: 13px;
  line-height: 1.6;
}

/* ── 사용자 버블 ─────────────────────────────────────────── */
.user-bubble {
  background: #1a1a2e;
  color: #fff;
  border-radius: 14px 14px 2px 14px;
  white-space: pre-wrap;
}

/* ── AI 버블 ─────────────────────────────────────────────── */
.assistant-bubble {
  background: #fff;
  color: #222;
  border-radius: 14px 14px 14px 2px;
  border: 1px solid #e8e8e8;
  box-shadow: 0 1px 4px rgba(0,0,0,.06);
}

/* ── 마크다운 렌더링 영역 ─────────────────────────────────── */
.md-body { line-height: 1.7; }

/* 제목 */
.md-body :deep(h1),
.md-body :deep(h2),
.md-body :deep(h3) {
  margin: 10px 0 6px;
  font-weight: 700;
  color: #1a1a2e;
  line-height: 1.3;
}
.md-body :deep(h1) { font-size: 16px; border-bottom: 2px solid #e94560; padding-bottom: 4px; }
.md-body :deep(h2) { font-size: 14px; border-bottom: 1px solid #eee; padding-bottom: 3px; }
.md-body :deep(h3) { font-size: 13px; }

/* 단락 */
.md-body :deep(p) { margin: 4px 0 8px; }

/* 굵게·기울임 */
.md-body :deep(strong) { font-weight: 700; color: #1a1a2e; }
.md-body :deep(em)     { font-style: italic; color: #555; }

/* 목록 */
.md-body :deep(ul),
.md-body :deep(ol) {
  margin: 4px 0 8px;
  padding-left: 20px;
}
.md-body :deep(li) { margin: 3px 0; }

/* 표 */
.md-body :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 8px 0;
  font-size: 12px;
}
.md-body :deep(th) {
  background: #1a1a2e;
  color: #fff;
  padding: 6px 10px;
  text-align: left;
  font-weight: 600;
}
.md-body :deep(td) {
  padding: 5px 10px;
  border-bottom: 1px solid #eee;
}
.md-body :deep(tr:nth-child(even) td) { background: #f8f9fb; }
.md-body :deep(tr:hover td)           { background: #eef2ff; }

/* 인라인 코드 */
.md-body :deep(code) {
  background: #f0f0f0;
  border-radius: 4px;
  padding: 1px 5px;
  font-family: 'SF Mono', 'Fira Code', monospace;
  font-size: 11px;
  color: #e94560;
}

/* 코드 블록 */
.md-body :deep(pre) {
  background: #1a1a2e;
  border-radius: 8px;
  padding: 12px;
  overflow-x: auto;
  margin: 8px 0;
}
.md-body :deep(pre code) {
  background: none;
  color: #cdd6f4;
  font-size: 12px;
  padding: 0;
}

/* 구분선 */
.md-body :deep(hr) {
  border: none;
  border-top: 1px solid #eee;
  margin: 10px 0;
}

/* blockquote */
.md-body :deep(blockquote) {
  border-left: 3px solid #e94560;
  margin: 6px 0;
  padding: 4px 12px;
  background: #fff5f5;
  color: #555;
  font-style: italic;
}

/* ── 로딩 인디케이터 (점 3개 애니메이션) ─────────────────── */
.thinking-bubble {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 12px 16px;
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #aaa;
  animation: bounce 1.2s infinite ease-in-out;
}
.dot:nth-child(1) { animation-delay: 0s; }
.dot:nth-child(2) { animation-delay: .2s; }
.dot:nth-child(3) { animation-delay: .4s; }
@keyframes bounce {
  0%, 80%, 100% { transform: scale(.7); opacity: .5; }
  40%           { transform: scale(1);  opacity: 1; }
}

/* ── 입력 영역 ───────────────────────────────────────────── */
.input-row {
  display: flex;
  border-top: 1px solid #eee;
  background: #fff;
}
.input-row input {
  flex: 1;
  padding: 12px 16px;
  border: none;
  outline: none;
  font-size: 13px;
  color: #222;
  background: transparent;
}
.input-row input::placeholder { color: #bbb; }
.input-row input:disabled     { opacity: .6; }
.input-row button {
  padding: 12px 20px;
  background: #1a1a2e;
  color: #fff;
  border: none;
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  transition: background .2s;
  min-width: 68px;
}
.input-row button:hover:not(:disabled) { background: #0f3460; }
.input-row button:disabled { opacity: .45; cursor: not-allowed; }

/* ── 스크롤바 ─────────────────────────────────────────────── */
.messages::-webkit-scrollbar       { width: 4px; }
.messages::-webkit-scrollbar-track { background: transparent; }
.messages::-webkit-scrollbar-thumb { background: #ddd; border-radius: 4px; }
</style>
