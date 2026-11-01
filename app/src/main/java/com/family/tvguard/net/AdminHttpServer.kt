package com.family.tvguard.net

import android.util.Log
import com.family.tvguard.TvGuardApp
import com.family.tvguard.data.GuardPrefs
import com.family.tvguard.service.GuardService
import com.family.tvguard.util.UsageAccessHelper
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * LAN parent control API + simple mobile HTML admin.
 *
 * GET  /v1/status
 * POST /v1/unlock   {"hours":2}  Authorization: Bearer <token> | ?token= | X-Token
 * POST /v1/lock
 * POST /v1/cancel-unlock
 * GET  /admin/
 */
class AdminHttpServer(
    private val prefs: GuardPrefs,
    private val onStateChanged: (String) -> Unit
) : NanoHTTPD(GuardPrefs.HTTP_PORT) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri?.substringBefore('?') ?: "/"
        val method = session.method
        Log.i(TvGuardApp.TAG, "HTTP $method $uri")
        return try {
            when {
                uri == "/v1/status" && method == Method.GET -> statusJson()
                uri == "/v1/unlock" && method == Method.POST -> unlock(session)
                uri == "/v1/lock" && method == Method.POST -> lock(session)
                uri == "/v1/cancel-unlock" && method == Method.POST -> cancel(session)
                uri == "/admin" || uri == "/admin/" || uri == "/" -> adminHtml()
                uri == "/admin/api/unlock" && method == Method.POST -> unlockFromForm(session)
                uri == "/admin/api/lock" && method == Method.POST -> lockFromForm(session)
                uri == "/admin/api/cancel" && method == Method.POST -> cancelFromForm(session)
                uri == "/admin/api/force-quiz" && method == Method.POST -> forceQuizFromForm(session)
                uri == "/v1/force-quiz" && method == Method.POST -> forceQuiz(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
            }
        } catch (e: Exception) {
            Log.e(TvGuardApp.TAG, "HTTP error: ${e.message}", e)
            jsonError(500, e.message ?: "error")
        }
    }

    private fun statusJson(): Response {
        // Read-only snapshot — do not flushSessionUsage (that would invent watch time).
        prefs.clampDailyUsedToElapsedDay()
        val now = System.currentTimeMillis()
        val app = TvGuardApp.instance
        val usageGranted = UsageAccessHelper.hasUsageAccess(app)
        val svc = GuardService.instance
        val o = JSONObject()
            .put("locked", prefs.isEffectivelyLocked(now))
            .put("prefsLocked", prefs.locked)
            .put("unlockUntilEpochMs", prefs.unlockUntilEpochMs)
            .put("remainingUnlockMs", prefs.remainingUnlockMs(now))
            .put("dailyUsedMs", prefs.dailyUsedMsSnapshot(now))
            .put("dailyDate", prefs.dailyDate)
            .put("dailyQuotaMs", GuardPrefs.DAILY_QUOTA_MS)
            .put("remainingQuotaMs", prefs.remainingQuotaMs(now))
            .put("port", GuardPrefs.HTTP_PORT)
            .put("version", GuardService.VERSION_NAME)
            .put("versionCode", 14)
            .put("quizPending", prefs.quizPending)
            .put("msSinceLastQuiz", prefs.msSinceLastQuiz)
            .put("quizIntervalMs", GuardPrefs.QUIZ_INTERVAL_MS)
            .put("currentQuizId", prefs.currentQuizId)
            .put("usageAccessGranted", usageGranted)
            .put("topDetectionMode", if (usageGranted) "usage_stats" else "heuristic")
            .put("lastTopPackage", svc?.lastTopPackage ?: JSONObject.NULL)
            .put("lastTopSource", svc?.lastTopSource ?: "none")
            .put("guardServiceRunning", svc != null)
            .put("deviceOwner", false)
            .put("deviceOwnerNote", "Skipped: requires factory-reset / dpm; unsafe for family Hisense TV")
            .put("keepAliveIntervalMs", 5L * 60_000)
        return jsonOk(o)
    }

    private fun unlock(session: IHTTPSession): Response {
        if (!authorized(session)) return unauthorized(session)
        val body = readBody(session)
        var hours = GuardPrefs.DEFAULT_UNLOCK_HOURS
        if (body.isNotBlank()) {
            try {
                hours = JSONObject(body).optDouble("hours", hours)
            } catch (_: Exception) {}
        }
        if (hours <= 0) hours = GuardPrefs.DEFAULT_UNLOCK_HOURS
        prefs.unlockHours(hours)
        onStateChanged("unlock ${hours}h")
        return statusJson()
    }

    private fun lock(session: IHTTPSession): Response {
        if (!authorized(session)) return unauthorized(session)
        prefs.lockNow()
        onStateChanged("lock")
        return statusJson()
    }

    private fun cancel(session: IHTTPSession): Response {
        if (!authorized(session)) return unauthorized(session)
        prefs.cancelUnlock()
        onStateChanged("cancel-unlock")
        return statusJson()
    }

    /** Form posts from /admin HTML embed token in body field `token`. */
    private fun unlockFromForm(session: IHTTPSession): Response {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val token = map["token"] ?: session.parms["token"]
        if (!prefs.tokenMatches(token) && !prefs.tokenMatches("Bearer $token")) {
            // also accept header
            if (!authorized(session)) return redirectAdmin("token 无效")
        }
        val hours = (map["hours"] ?: session.parms["hours"] ?: "2").toDoubleOrNull()
            ?: GuardPrefs.DEFAULT_UNLOCK_HOURS
        prefs.unlockHours(hours)
        onStateChanged("unlock-form ${hours}h")
        return redirectAdmin("已解锁 ${hours} 小时")
    }

    private fun lockFromForm(session: IHTTPSession): Response {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val token = map["token"] ?: session.parms["token"]
        if (!prefs.tokenMatches(token) && !prefs.tokenMatches("Bearer $token") && !authorized(session)) {
            return redirectAdmin("token 无效")
        }
        prefs.lockNow()
        onStateChanged("lock-form")
        return redirectAdmin("已立即锁屏")
    }

    private fun cancelFromForm(session: IHTTPSession): Response {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val token = map["token"] ?: session.parms["token"]
        if (!prefs.tokenMatches(token) && !prefs.tokenMatches("Bearer $token") && !authorized(session)) {
            return redirectAdmin("token 无效")
        }
        prefs.cancelUnlock()
        onStateChanged("cancel-form")
        return redirectAdmin("已取消解锁")
    }

    private fun forceQuiz(session: IHTTPSession): Response {
        if (!authorized(session)) return unauthorized(session)
        prefs.quizPending = true
        prefs.sessionStartEpochMs = 0L
        prefs.ensureQuizSelected()
        onStateChanged("force-quiz")
        return statusJson()
    }

    private fun forceQuizFromForm(session: IHTTPSession): Response {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val token = map["token"] ?: session.parms["token"]
        if (!prefs.tokenMatches(token) && !prefs.tokenMatches("Bearer $token") && !authorized(session)) {
            return redirectAdmin("token 无效")
        }
        prefs.quizPending = true
        prefs.sessionStartEpochMs = 0L
        prefs.ensureQuizSelected()
        onStateChanged("force-quiz-form")
        return redirectAdmin("已触发答题锁屏")
    }

    /**
     * Accept any of:
     * - Authorization: Bearer <token>  (or raw token)
     * - X-Token: <token>
     * - ?token=<token> query (NanoHTTPD parms)
     *
     * Some mobile in-app browsers (WeChat etc.) strip Authorization; query/X-Token remain.
     */
    private fun authorized(session: IHTTPSession): Boolean {
        val auth = session.headers["authorization"]
        if (prefs.tokenMatches(auth)) return true

        val xToken = session.headers["x-token"]
        if (!xToken.isNullOrBlank() &&
            (prefs.tokenMatches(xToken) || prefs.tokenMatches("Bearer $xToken"))
        ) return true

        val qToken = session.parms["token"]
        if (!qToken.isNullOrBlank() &&
            (prefs.tokenMatches(qToken) || prefs.tokenMatches("Bearer $qToken"))
        ) return true

        return false
    }

    private fun unauthorized(session: IHTTPSession): Response {
        val hasAuth = !session.headers["authorization"].isNullOrBlank()
        val hasX = !session.headers["x-token"].isNullOrBlank()
        val hasQ = !session.parms["token"].isNullOrBlank()
        Log.w(
            TvGuardApp.TAG,
            "HTTP 401 unauthorized uri=${session.uri} hasAuthorization=$hasAuth hasXToken=$hasX hasQueryToken=$hasQ"
        )
        return jsonError(401, "unauthorized")
    }

    private fun readBody(session: IHTTPSession): String {
        val map = HashMap<String, String>()
        return try {
            session.parseBody(map)
            map["postData"] ?: ""
        } catch (_: Exception) {
            try {
                val len = session.headers["content-length"]?.toIntOrNull() ?: 0
                if (len <= 0) return ""
                val br = BufferedReader(InputStreamReader(session.inputStream))
                // NanoHTTPD usually consumes via parseBody
                ""
            } catch (_: Exception) {
                ""
            }
        }
    }

    private fun adminHtml(): Response {
        val st = run {
            prefs.clampDailyUsedToElapsedDay()
            val now = System.currentTimeMillis()
            val locked = prefs.isEffectivelyLocked(now)
            val rem = prefs.remainingUnlockMs(now) / 60000
            val used = prefs.dailyUsedMsSnapshot(now) / 60000
            Triple(locked, rem, used)
        }
        val html = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>家庭电视锁</title>
<style>
  body{font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;background:#0b1220;color:#fff;margin:0;padding:24px}
  h1{font-size:22px;margin:0 0 8px}
  .sub{color:#90a4ae;margin-bottom:20px;font-size:14px}
  .card{background:#152033;border-radius:16px;padding:20px;margin-bottom:16px}
  .status{font-size:18px;margin-bottom:8px}
  button{display:block;width:100%;padding:16px;margin:10px 0;border:0;border-radius:12px;font-size:18px;font-weight:600;color:#fff}
  .u{background:#1e88e5}.l{background:#e53935}.c{background:#546e7a}
  input{width:100%;padding:12px;border-radius:8px;border:1px solid #37474f;background:#0b1220;color:#fff;box-sizing:border-box;margin:8px 0}
  .ok{color:#81c784;font-size:14px}
</style>
</head>
<body>
  <h1>家庭电视锁</h1>
  <div class="sub">同一 WiFi · 家长控制 · M2.12 · Usage Access 建议开启</div>
  <div class="card">
    <div class="status">${if (st.first) "已锁定" else "解锁中（剩余约 ${st.second} 分钟）"}</div>
    <div class="sub">今日已看 ${st.third} / 120 分钟</div>
  </div>
  <div class="card">
    <label>家长 Token（电视锁屏页 / adb logcat / parent_token.txt）</label>
    <input id="token" type="text" placeholder="粘贴 parentToken" autocomplete="off"/>
    <button class="u" type="button" onclick="api('unlock',{hours:2})">解锁 2 小时</button>
    <button class="l" type="button" onclick="api('lock')">立即锁屏</button>
    <button class="c" type="button" onclick="api('cancel-unlock')">取消解锁</button>
    <button class="c" type="button" style="background:#8e24aa" onclick="api('force-quiz')">立即出题（测试）</button>
  </div>
  <p class="ok" id="msg"></p>
<script>
(function(){
  function qp(name){
    var s=location.search||'';
    if(s.charAt(0)==='?') s=s.substring(1);
    var parts=s.split('&');
    for(var i=0;i<parts.length;i++){
      var kv=parts[i].split('=');
      if(decodeURIComponent(kv[0]||'')===name){
        return decodeURIComponent((kv[1]||'').replace(/\+/g,' '));
      }
    }
    return '';
  }
  var fromQ=qp('token');
  var t=fromQ || localStorage.getItem('tvguard_token') || '';
  if(fromQ){ localStorage.setItem('tvguard_token', fromQ.trim()); }
  document.getElementById('token').value=t;
  document.getElementById('token').addEventListener('change',function(){
    localStorage.setItem('tvguard_token', this.value.trim());
  });
  document.getElementById('token').addEventListener('input',function(){
    localStorage.setItem('tvguard_token', this.value.trim());
  });
  var msgQ=qp('msg');
  if(msgQ){ document.getElementById('msg').textContent=msgQ; }
})();
function tok(){
  var t=document.getElementById('token').value.trim();
  if(!t){alert('请填写 Token');return null;}
  localStorage.setItem('tvguard_token', t);
  return t;
}
function applyStatus(s){
  var statusEl=document.querySelector('.status');
  var sub=document.querySelectorAll('.card .sub')[0];
  if(statusEl){
    if(s.locked){statusEl.textContent='已锁定';}
    else{statusEl.textContent='解锁中（剩余约 '+Math.floor((s.remainingUnlockMs||0)/60000)+' 分钟）';}
  }
  if(sub){sub.textContent='今日已看 '+Math.floor((s.dailyUsedMs||0)/60000)+' / 120 分钟';}
}
function api(path, body){
  var t=tok(); if(!t) return;
  var msg=document.getElementById('msg');
  msg.textContent='请求中…';
  // Send token three ways: Authorization (curl/desktop), X-Token, and ?token=
  // (some mobile WebViews strip Authorization).
  var url='/v1/'+path+'?token='+encodeURIComponent(t);
  fetch(url,{
    method:'POST',
    headers:{
      'Authorization':'Bearer '+t,
      'X-Token':t,
      'Content-Type':'application/json'
    },
    body: body?JSON.stringify(body):'{}'
  }).then(function(r){
    return r.json().then(function(j){ return {ok:r.ok,status:r.status,j:j}; }).catch(function(){
      return {ok:r.ok,status:r.status,j:null};
    });
  }).then(function(x){
    if(!x.ok){
      msg.textContent='失败 HTTP '+x.status+' '+(x.j&&x.j.error?x.j.error:'');
      return;
    }
    applyStatus(x.j);
    if(path==='lock'||path==='cancel-unlock'){ msg.textContent='已立即锁屏'; }
    else if(path==='unlock'){ msg.textContent='已解锁'; }
    else if(path==='force-quiz'){ msg.textContent='已触发答题锁屏'; }
    else { msg.textContent='OK'; }
  }).catch(function(e){
    msg.textContent='网络错误: '+e;
  });
}
</script>
</body>
</html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun redirectAdmin(msg: String): Response {
        val r = newFixedLengthResponse(Response.Status.REDIRECT, MIME_PLAINTEXT, msg)
        r.addHeader("Location", "/admin/?msg=" + java.net.URLEncoder.encode(msg, "UTF-8"))
        return r
    }

    private fun jsonOk(o: JSONObject): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", o.toString())

    private fun jsonError(code: Int, msg: String): Response {
        val status = when (code) {
            401 -> Response.Status.UNAUTHORIZED
            404 -> Response.Status.NOT_FOUND
            else -> Response.Status.INTERNAL_ERROR
        }
        val o = JSONObject().put("error", msg)
        return newFixedLengthResponse(status, "application/json; charset=utf-8", o.toString())
    }
}
