/**
 * Minimal API helper.
 *
 * Every call attaches Authorization and (for POST) Content-Type headers.
 * Returns { status, ok, data } where data is the parsed JSON body or an
 * error string when the response is not valid JSON.
 */
export async function apiFetch(path, { method = 'GET', token = '', body = null } = {}) {
  const headers = {}

  if (token) {
    headers['Authorization'] = `Bearer ${token}`
  }

  const options = { method, headers }

  if (body !== null) {
    headers['Content-Type'] = 'application/json'
    options.body = JSON.stringify(body)
  }

  try {
    const res = await fetch(path, options)
    let data
    const text = await res.text()
    try {
      data = text ? JSON.parse(text) : null
    } catch {
      data = text
    }
    return { status: res.status, ok: res.ok, data }
  } catch (err) {
    return { status: null, ok: false, data: String(err) }
  }
}
