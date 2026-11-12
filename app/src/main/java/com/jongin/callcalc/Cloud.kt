package com.jongin.callcalc

import android.app.Activity
import android.content.Context
import android.os.CancellationSignal
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query

/**
 * 서버 (Firebase · call-calc 프로젝트, 계산기 전용)
 *  - 구글 로그인
 *  - 팀(2인 1조): teams/{코드}  { owner, members[], names{uid: 이름}, createdAt }
 *  - 수락한 콜 알림: teams/{코드}/shares/{id}  (팀원만 읽기, 하루 지나면 지움)
 *  - 콜 기록: users/{uid}/calls/{id}  (나만 읽기·쓰기)
 * 서버에는 수락까지 한 콜만 올라간다. 수락 창을 보기만 한 콜·계산만 한 콜은 올리지 않음.
 */
object Cloud {
    private val auth get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseFirestore.getInstance()

    val uid: String? get() = auth.currentUser?.uid
    val signedIn: Boolean get() = auth.currentUser != null
    val displayName: String get() = auth.currentUser?.displayName?.takeIf { it.isNotBlank() } ?: auth.currentUser?.email ?: "나"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cloud", Context.MODE_PRIVATE)

    /** 내가 들어가 있는 팀 코드 (없으면 null) */
    fun teamCode(ctx: Context): String? = prefs(ctx).getString("team", null)
    private fun setTeamCode(ctx: Context, code: String?) = prefs(ctx).edit().putString("team", code).apply()

    /** 수락하면 팀에 보내기 / 콜 기록 올리기 */
    fun shareOn(ctx: Context) = prefs(ctx).getBoolean("shareOn", true)
    fun setShareOn(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("shareOn", v).apply()
    fun recordOn(ctx: Context) = prefs(ctx).getBoolean("recordOn", true)
    fun setRecordOn(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("recordOn", v).apply()

    // ───────── 관리자가 정하는 것 (이 폰에 저장해 두고 씀) ─────────

    /** 기능 이름: A 오더, B 뒷방, check 콜 확인, record 기록, team 팀 */
    val FEATURES = listOf("A" to "A 오더", "B" to "B 뒷방", "check" to "콜 확인 (TMAP)", "record" to "콜 기록", "team" to "팀 (2인 1조)")

    fun banned(ctx: Context) = prefs(ctx).getBoolean("banned", false)

    /** 관리자가 막지 않았으면 true (로그인 안 했으면 다 허용) */
    fun allowed(ctx: Context, feature: String): Boolean {
        val p = prefs(ctx)
        return !p.getBoolean("banned", false) && p.getBoolean("f_$feature", true)
    }

    fun isAdmin(ctx: Context) = signedIn && prefs(ctx).getString("adminUid", null) == uid

    /**
     * 내 정보 올리기 (관리자 화면에 보이는 것: 이름, 메일, 모드, 버전, 마지막 접속, 폰 권한 상태)
     * + 관리자가 정한 기능 허락·접근 금지를 받아서 이 폰에 저장. done 은 바뀐 게 있으면 true
     */
    fun sync(ctx: Context, perms: Map<String, Boolean>, mode: String, version: String, done: ((Boolean) -> Unit)? = null) {
        val me = uid ?: return
        val p = prefs(ctx)
        val ref = db.collection("users").document(me)
        ref.set(mapOf(
            "name" to displayName,
            "email" to auth.currentUser?.email,
            "mode" to mode,
            "version" to version,
            "lastSeen" to System.currentTimeMillis(),
            "perms" to perms,
        ), com.google.firebase.firestore.SetOptions.merge())
        ref.get().addOnSuccessListener { d ->
            val before = p.all.filterKeys { it == "banned" || it.startsWith("f_") }
            val e = p.edit().putBoolean("banned", d.getBoolean("banned") == true)
            @Suppress("UNCHECKED_CAST")
            val f = d.get("features") as? Map<String, Any?> ?: emptyMap()
            for ((k, _) in FEATURES) e.putBoolean("f_$k", f[k] != false)
            e.apply()
            val after = p.all.filterKeys { it == "banned" || it.startsWith("f_") }
            db.collection("config").document("admin").get()
                .addOnSuccessListener { a -> p.edit().putString("adminUid", a.getString("uid")).putBoolean("adminSet", a.exists()).apply() }
                .addOnCompleteListener { done?.invoke(before != after) }
        }
    }

    /** 관리자가 아직 없음 (처음 한 번 [관리자로 등록]) */
    fun adminMissing(ctx: Context) = !prefs(ctx).getBoolean("adminSet", true)

    fun claimAdmin(ctx: Context, done: (String?) -> Unit) {
        val me = uid ?: return done("먼저 로그인해 주세요")
        db.collection("config").document("admin").set(mapOf("uid" to me, "email" to auth.currentUser?.email))
            .addOnSuccessListener { prefs(ctx).edit().putString("adminUid", me).putBoolean("adminSet", true).apply(); done(null) }
            .addOnFailureListener { done("이미 관리자가 있어요") }
    }

    // ───────── 관리자 화면용 ─────────

    data class Person(val uid: String, val data: Map<String, Any?>) {
        val name get() = data["name"] as? String ?: "(이름 없음)"
        val email get() = data["email"] as? String ?: ""
        val banned get() = data["banned"] == true
        @Suppress("UNCHECKED_CAST") val perms get() = data["perms"] as? Map<String, Any?> ?: emptyMap()
        @Suppress("UNCHECKED_CAST") val features get() = data["features"] as? Map<String, Any?> ?: emptyMap()
        val lastSeen get() = (data["lastSeen"] as? Number)?.toLong() ?: 0L
    }

    fun loadPeople(done: (List<Person>?, String?) -> Unit) {
        db.collection("users").get()
            .addOnSuccessListener { q -> done(q.documents.map { Person(it.id, it.data.orEmpty()) }.sortedByDescending { it.lastSeen }, null) }
            .addOnFailureListener { done(null, failText(it)) }
    }

    fun setPerson(uid: String, fields: Map<String, Any?>, done: (String?) -> Unit) {
        db.collection("users").document(uid).set(fields, com.google.firebase.firestore.SetOptions.merge())
            .addOnSuccessListener { done(null) }.addOnFailureListener { done(failText(it)) }
    }

    data class Team(val code: String, val data: Map<String, Any?>) {
        @Suppress("UNCHECKED_CAST") val names get() = data["names"] as? Map<String, Any?> ?: emptyMap()
        @Suppress("UNCHECKED_CAST") val members get() = data["members"] as? List<String> ?: emptyList()
    }

    fun loadTeams(done: (List<Team>?, String?) -> Unit) {
        db.collection("teams").get()
            .addOnSuccessListener { q -> done(q.documents.map { Team(it.id, it.data.orEmpty()) }, null) }
            .addOnFailureListener { done(null, failText(it)) }
    }

    fun removeMember(code: String, member: String, done: () -> Unit) {
        db.collection("teams").document(code).update(mapOf(
            "members" to FieldValue.arrayRemove(member), "names.$member" to FieldValue.delete(),
        )).addOnCompleteListener { done() }
    }

    fun deleteTeam(code: String, done: () -> Unit) {
        db.collection("teams").document(code).delete().addOnCompleteListener { done() }
    }

    // ───────── 로그인 ─────────

    fun signIn(a: Activity, done: (String?) -> Unit) {
        val clientId = a.getString(R.string.default_web_client_id)
        val option = GetSignInWithGoogleOption.Builder(clientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        CredentialManager.create(a).getCredentialAsync(a, request, CancellationSignal(), a.mainExecutor,
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    val cred = result.credential
                    if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                        val google = GoogleIdTokenCredential.createFrom(cred.data)
                        auth.signInWithCredential(GoogleAuthProvider.getCredential(google.idToken, null))
                            .addOnSuccessListener {
                                done(null)
                                CallReaderService.instance?.cloudChanged()
                            }
                            .addOnFailureListener { done("로그인 실패: ${it.message}") }
                    } else done("구글 계정 정보를 못 받았어요")
                }

                override fun onError(e: GetCredentialException) {
                    done(if (e is GetCredentialCancellationException) null else "로그인 실패: ${e.message}")
                }
            })
    }

    fun signOut(ctx: Context) {
        auth.signOut()
        prefs(ctx).edit().remove("adminUid").apply() // 접근 금지는 로그아웃해도 그대로
        CallReaderService.instance?.cloudChanged()
    }

    // ───────── 팀 ─────────

    private const val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // 헷갈리는 0 O 1 I 뺌

    /** 팀 만들기 → 코드 */
    fun createTeam(ctx: Context, done: (String?, String?) -> Unit) {
        val me = uid ?: return done(null, "먼저 구글 로그인을 해 주세요")
        val code = (1..6).map { CODE_CHARS.random() }.joinToString("")
        db.collection("teams").document(code).set(mapOf(
            "owner" to me,
            "members" to listOf(me),
            "names" to mapOf(me to displayName),
            "createdAt" to System.currentTimeMillis(),
        )).addOnSuccessListener {
            setTeamCode(ctx, code)
            CallReaderService.instance?.cloudChanged()
            done(code, null)
        }.addOnFailureListener { done(null, failText(it)) }
    }

    /** 코드로 팀 들어가기 */
    fun joinTeam(ctx: Context, raw: String, done: (String?) -> Unit) {
        val me = uid ?: return done("먼저 구글 로그인을 해 주세요")
        val code = raw.uppercase().filter { it in CODE_CHARS }
        if (code.length != 6) return done("코드는 6글자예요")
        db.collection("teams").document(code).update(mapOf(
            "members" to FieldValue.arrayUnion(me),
            "names.$me" to displayName,
        )).addOnSuccessListener {
            setTeamCode(ctx, code)
            CallReaderService.instance?.cloudChanged()
            done(null)
        }.addOnFailureListener { done("코드가 맞지 않거나 팀이 없어요") }
    }

    fun leaveTeam(ctx: Context, done: () -> Unit) {
        val me = uid
        val code = teamCode(ctx)
        setTeamCode(ctx, null)
        CallReaderService.instance?.cloudChanged()
        if (me == null || code == null) return done()
        db.collection("teams").document(code).update(mapOf(
            "members" to FieldValue.arrayRemove(me),
            "names.$me" to FieldValue.delete(),
        )).addOnCompleteListener { done() }
    }

    /** 팀원 이름들 */
    fun teamNames(ctx: Context, done: (List<String>?) -> Unit) {
        val code = teamCode(ctx) ?: return done(null)
        db.collection("teams").document(code).get()
            .addOnSuccessListener { d ->
                @Suppress("UNCHECKED_CAST")
                val names = (d.get("names") as? Map<String, Any?>)?.values?.map { it.toString() }
                done(names ?: emptyList())
            }
            .addOnFailureListener { done(null) }
    }

    // ───────── 보내기 · 기록 ─────────

    /** 수락한 콜을 팀에 보내기 */
    fun share(ctx: Context, data: Map<String, Any?>, done: (String?) -> Unit) {
        val me = uid ?: return done("로그인이 안 되어 있어요")
        val code = teamCode(ctx) ?: return done("팀이 없어요")
        val doc = data + mapOf("from" to me, "fromName" to displayName, "at" to System.currentTimeMillis())
        db.collection("teams").document(code).collection("shares").add(doc)
            .addOnSuccessListener { done(null) }
            .addOnFailureListener { done(failText(it)) }
    }

    /** 수락한 콜 기록 한 줄 */
    fun record(data: Map<String, Any?>, done: (String?) -> Unit) {
        val me = uid ?: return done("로그인이 안 되어 있어요")
        db.collection("users").document(me).collection("calls").add(data + mapOf("at" to System.currentTimeMillis()))
            .addOnSuccessListener { done(null) }
            .addOnFailureListener { done(failText(it)) }
    }

    data class Call(val id: String, val data: Map<String, Any?>) {
        val at: Long get() = (data["at"] as? Number)?.toLong() ?: 0L
        fun num(k: String): Double? = (data[k] as? Number)?.toDouble()
        fun str(k: String): String? = data[k] as? String
    }

    fun loadCalls(of: String? = null, done: (List<Call>?, String?) -> Unit) {
        val me = of ?: uid ?: return done(null, "먼저 구글 로그인을 해 주세요")
        db.collection("users").document(me).collection("calls")
            .orderBy("at", Query.Direction.DESCENDING).limit(2000).get()
            .addOnSuccessListener { q -> done(q.documents.map { Call(it.id, it.data.orEmpty()) }, null) }
            .addOnFailureListener { done(null, failText(it)) }
    }

    fun deleteCall(id: String, of: String? = null, done: () -> Unit) {
        val me = of ?: uid ?: return done()
        db.collection("users").document(me).collection("calls").document(id).delete().addOnCompleteListener { done() }
    }

    /**
     * 팀 알림 듣기: 팀원이 수락해서 보낸 것만 (내가 보낸 건 빼고, 처음 들을 때 10분 넘은 건 빼고).
     * 하루 지난 알림은 지운다.
     */
    fun listen(ctx: Context, onShare: (String, Map<String, Any?>) -> Unit): ListenerRegistration? {
        val me = uid ?: return null
        val code = teamCode(ctx) ?: return null
        val col = db.collection("teams").document(code).collection("shares")
        val now = System.currentTimeMillis()
        // 하루 지난 것 지우기
        col.whereLessThan("at", now - 24 * 3600_000L).get().addOnSuccessListener { q -> q.documents.forEach { it.reference.delete() } }
        val p = prefs(ctx)
        return col.whereGreaterThan("at", now - 10 * 60_000L).addSnapshotListener { q, _ ->
            q?.documentChanges?.forEach { ch ->
                if (ch.type != DocumentChange.Type.ADDED) return@forEach
                val d = ch.document
                if (d.getString("from") == me) return@forEach
                val seen = p.getStringSet("seen", emptySet())!!
                if (d.id in seen) return@forEach
                p.edit().putStringSet("seen", (seen.toList().takeLast(50) + d.id).toSet()).apply()
                onShare(d.id, d.data)
            }
        }
    }

    private fun failText(e: Exception): String {
        val m = e.message.orEmpty()
        return when {
            "PERMISSION_DENIED" in m -> "서버 권한이 막혀 있어요 (Firestore 규칙을 확인해 주세요)"
            "UNAVAILABLE" in m || "network" in m.lowercase() -> "인터넷 연결을 확인해 주세요"
            else -> "서버 오류: $m"
        }
    }
}
