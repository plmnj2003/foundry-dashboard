import axios from 'axios'

const BASE = '/api'

export function useChatApi() {
  async function sendChat(question) {
    const res = await axios.post(`${BASE}/ai/chat`, { question })
    return res.data // { answer, type, sources[], confidence }
  }

  async function uploadDocument(file) {
    const form = new FormData()
    form.append('file', file)
    const res = await axios.post(`${BASE}/documents/upload`, form, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    return res.data
  }

  async function listDocuments() {
    const res = await axios.get(`${BASE}/documents`)
    return res.data
  }

  async function deleteDocument(id) {
    await axios.delete(`${BASE}/documents/${id}`)
  }

  return { sendChat, uploadDocument, listDocuments, deleteDocument }
}
