<template>
  <section class="docs-manager">
    <div class="docs-header">
      <div>
        <h2>임베딩된 문서</h2>
        <span class="docs-count">{{ documents.length }}건</span>
      </div>
      <div class="docs-actions">
        <button class="refresh-btn" @click="refresh" :disabled="loading">
          {{ loading ? '불러오는 중' : '새로고침' }}
        </button>
        <button class="back-btn" @click="$emit('close')">← 대시보드로</button>
      </div>
    </div>

    <div v-if="loading && !documents.length" class="docs-state">불러오는 중...</div>
    <div v-else-if="error" class="docs-state docs-error">{{ error }}</div>
    <div v-else-if="!documents.length" class="docs-state">업로드된 문서가 없습니다. 챗봇의 📎 버튼으로 업로드해보세요.</div>

    <table v-else class="docs-table">
      <thead>
        <tr>
          <th>파일명</th>
          <th>유형</th>
          <th>크기</th>
          <th>청크 수</th>
          <th>상태</th>
          <th>업로드 시각</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="d in documents" :key="d.id">
          <td class="filename">{{ d.filename }}</td>
          <td>{{ d.content_type || '-' }}</td>
          <td>{{ formatSize(d.file_size) }}</td>
          <td>{{ d.chunk_count }}</td>
          <td><span :class="['badge', d.status.toLowerCase()]">{{ statusLabel(d.status) }}</span></td>
          <td>{{ formatDate(d.uploaded_at) }}</td>
          <td>
            <button class="delete-btn" @click="remove(d)" :disabled="deletingId === d.id">
              {{ deletingId === d.id ? '삭제중' : '삭제' }}
            </button>
          </td>
        </tr>
      </tbody>
    </table>
  </section>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useChatApi } from '../composables/useChatApi.js'

defineEmits(['close'])
const { listDocuments, deleteDocument } = useChatApi()

const documents = ref([])
const loading = ref(false)
const error = ref(null)
const deletingId = ref(null)

async function refresh() {
  loading.value = true
  error.value = null
  try {
    documents.value = await listDocuments()
  } catch {
    error.value = '문서 목록을 불러오지 못했습니다.'
  } finally {
    loading.value = false
  }
}

async function remove(doc) {
  if (!confirm(`"${doc.filename}" 문서를 삭제할까요? 관련된 모든 청크가 함께 삭제됩니다.`)) return
  deletingId.value = doc.id
  try {
    await deleteDocument(doc.id)
    documents.value = documents.value.filter(d => d.id !== doc.id)
  } catch {
    error.value = '삭제에 실패했습니다. 잠시 후 다시 시도해주세요.'
  } finally {
    deletingId.value = null
  }
}

function formatSize(bytes) {
  if (!bytes) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function statusLabel(status) {
  return { COMPLETED: '완료', PROCESSING: '처리중', FAILED: '실패' }[status] || status
}

function formatDate(iso) {
  return new Date(iso).toLocaleString('ko-KR')
}

onMounted(refresh)
</script>

<style scoped>
.docs-manager {
  background: #fff;
  border-radius: 8px;
  padding: 20px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, .08);
}

.docs-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.docs-header h2 { font-size: 18px; color: #1a1a2e; display: inline; }
.docs-count { margin-left: 8px; font-size: 13px; color: #888; }

.docs-actions { display: flex; gap: 8px; }
.refresh-btn, .back-btn {
  padding: 8px 14px;
  border-radius: 6px;
  border: 1px solid #ddd;
  background: #fff;
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  color: #1a1a2e;
}
.refresh-btn:hover:not(:disabled), .back-btn:hover { background: #f0f2f5; }
.refresh-btn:disabled { opacity: .5; cursor: not-allowed; }

.docs-state {
  text-align: center;
  padding: 60px 20px;
  color: #888;
  font-size: 14px;
}
.docs-error { color: #e94560; }

.docs-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.docs-table th {
  background: #f8f9fa;
  padding: 10px 12px;
  text-align: left;
  color: #666;
  font-weight: 600;
  border-bottom: 1px solid #eee;
}
.docs-table td { padding: 10px 12px; border-bottom: 1px solid #f0f0f0; }
.docs-table .filename { font-weight: 600; color: #1a1a2e; }

.badge { padding: 2px 8px; border-radius: 12px; font-size: 11px; font-weight: 600; }
.badge.completed { background: #d4edda; color: #155724; }
.badge.processing { background: #fff3cd; color: #856404; }
.badge.failed { background: #f8d7da; color: #721c24; }

.delete-btn {
  padding: 5px 12px;
  border-radius: 6px;
  border: 1px solid #e94560;
  background: #fff;
  color: #e94560;
  cursor: pointer;
  font-size: 12px;
  font-weight: 600;
}
.delete-btn:hover:not(:disabled) { background: #e94560; color: #fff; }
.delete-btn:disabled { opacity: .5; cursor: not-allowed; }
</style>
