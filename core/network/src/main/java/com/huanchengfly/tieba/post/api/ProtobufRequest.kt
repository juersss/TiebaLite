package com.huanchengfly.tieba.post.api

import com.huanchengfly.tieba.post.api.params.ClientUtils
import com.huanchengfly.tieba.post.api.session.SessionProviders
import android.content.Context
import android.os.Build
import com.huanchengfly.tieba.post.api.models.OAID
import com.huanchengfly.tieba.post.core.network.model.protos.AppPosInfo
import com.huanchengfly.tieba.post.core.network.model.protos.CommonRequest
import com.huanchengfly.tieba.post.core.network.model.protos.frsPage.AdParam
import com.huanchengfly.tieba.post.api.retrofit.RetrofitTiebaApi
import com.huanchengfly.tieba.post.api.retrofit.body.MyMultipartBody
import com.huanchengfly.tieba.post.core.common.toJson
import com.huanchengfly.tieba.post.api.internal.ApiEncoding.base64Encode
import com.huanchengfly.tieba.post.api.params.CuidUtils
import com.huanchengfly.tieba.post.api.params.DeviceUtils
import com.huanchengfly.tieba.post.api.params.MobileInfoUtil
import com.huanchengfly.tieba.post.api.params.UIDUtil
import com.squareup.wire.Message
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val BOUNDARY = "--------7da3d81520810*"

/**
 * 组装 protobuf 请求的 multipart 外壳。
 *
 * @param needSToken 是否附带 STOKEN 表单字段（多数接口需要）
 * @param stoken **显式指定**的 STOKEN；为空时回退到进程会话的当前账号。
 *   非当前账号的请求（如"添加账号"时按 uid 拉资料）必须传它——否则表单里的 STOKEN 属于
 *   当前登录账号、body 里的 BDUSS 属于待添加账号，服务端会按错的身份处理。
 */
fun buildProtobufRequestBody(
    data: Message<*, *>,
    clientVersion: ClientVersion = ClientVersion.TIEBA_V11,
    needSToken: Boolean = true,
    stoken: String? = null,
): MyMultipartBody {
    return MyMultipartBody.Builder(BOUNDARY)
        .apply {
            setType(MyMultipartBody.FORM)
            if (clientVersion != ClientVersion.TIEBA_V12 && clientVersion != ClientVersion.TIEBA_V12_POST && clientVersion != ClientVersion.TIEBA_V22) {
                addFormDataPart(Param.CLIENT_VERSION, clientVersion.version)
            }
            if (needSToken) {
                val effectiveSToken = stoken ?: SessionProviders.credential.getSToken()
                if (effectiveSToken != null) addFormDataPart(Param.STOKEN, effectiveSToken)
            }
            addFormDataPart("data", "file", data.encode().toRequestBody())
        }
        .build()
}

fun buildAdParam(
    load_count: Int = 0,
    refresh_count: Int = 4,
    yoga_lib_version: String? = "1.0"
): AdParam {
    return AdParam(
        load_count = load_count,
        refresh_count = refresh_count,
        yoga_lib_version = yoga_lib_version
    )
}

fun buildAppPosInfo(): AppPosInfo {
    return AppPosInfo(
        addr_timestamp = 0L,
        ap_connected = true,
        ap_mac = "02:00:00:00:00:00",
        asp_shown_info = "",
        coordinate_type = "BD09LL"
    )
}

/**
 * 组装 protobuf 请求的 `common` 字段。
 *
 * **账号口径（2026-10-20 修）**：`bduss`/`stoken` 一旦显式给出就**必须**用它，不能再回落到
 * 进程会话的当前账号——否则"添加账号"时按 uid 拉资料会拿当前账号的 BDUSS 去查，查到的是
 * 别人（或直接失败）。修复前只有 V11 分支认这两个入参，V12 / V22 / V12_POST 分支一律读
 * `SessionProviders.credential`。
 */
fun buildCommonRequest(
    context: Context = SessionProviders.appContext,
    clientVersion: ClientVersion = ClientVersion.TIEBA_V11,
    bduss: String? = null,
    stoken: String? = null,
    tbs: String? = null,
): CommonRequest = when (clientVersion) {
    ClientVersion.TIEBA_V11 -> {
        CommonRequest(
            BDUSS = bduss ?: SessionProviders.credential.getBduss(),
            _client_id = ClientUtils.clientId ?: RetrofitTiebaApi.randomClientId,
            _client_type = 2,
            _client_version = clientVersion.version,
            _os_version = "${Build.VERSION.SDK_INT}",
            _phone_imei = MobileInfoUtil.getIMEI(context),
            _timestamp = System.currentTimeMillis(),
            brand = Build.BRAND,
            c3_aid = UIDUtil.getAid(),
            cuid = CuidUtils.getNewCuid(),
            cuid_galaxy2 = CuidUtils.getNewCuid(),
            cuid_gid = "",
            from = "1024324o",
            is_teenager = 0,
            lego_lib_version = "3.0.0",
            model = Build.MODEL,
            net_type = 1,
            oaid = OAID().toJson(),
            pversion = "1.0.3",
            sample_id = ClientUtils.sampleId,
            stoken = stoken ?: SessionProviders.credential.getSToken(),
        )
    }

    ClientVersion.TIEBA_V12 -> {
        CommonRequest(
            BDUSS = bduss ?: SessionProviders.credential.getBduss(),
            _client_id = ClientUtils.clientId ?: RetrofitTiebaApi.randomClientId,
            _client_type = 2,
            _client_version = clientVersion.version,
            _os_version = "${Build.VERSION.SDK_INT}",
            _phone_imei = MobileInfoUtil.getIMEI(context),
            _timestamp = System.currentTimeMillis(),
            active_timestamp = ClientUtils.activeTimestamp,
            android_id = base64Encode(UIDUtil.getAndroidId("000")),
            brand = Build.BRAND,
            c3_aid = UIDUtil.getAid(),
            cmode = 1,
            cuid = CuidUtils.getNewCuid(),
            cuid_galaxy2 = CuidUtils.getNewCuid(),
            cuid_gid = "",
            event_day = SimpleDateFormat("yyyyMdd", Locale.getDefault()).format(
                Date(
                    System.currentTimeMillis()
                )
            ),
            extra = "",
            first_install_time = SessionProviders.clientConfig.appFirstInstallTime,
            framework_ver = "3340042",
            from = "1020031h",
            is_teenager = 0,
            last_update_time = SessionProviders.clientConfig.appLastUpdateTime,
            lego_lib_version = "3.0.0",
            model = Build.MODEL,
            net_type = 1,
            oaid = "",
            personalized_rec_switch = 1,
            pversion = "1.0.3",
            q_type = 0,
            sample_id = ClientUtils.sampleId,
            scr_dip = SessionProviders.deviceInfo.density.toDouble(),
            scr_h = getScreenHeight(),
            scr_w = getScreenWidth(),
            sdk_ver = "2.34.0",
            start_scheme = "",
            start_type = 1,
            stoken = stoken ?: SessionProviders.credential.getSToken(),
            swan_game_ver = "1038000",
            tbs = tbs,
            user_agent = getUserAgent("tieba/${clientVersion.version}"),
            z_id = SessionProviders.credential.getZId()
        )
    }

    ClientVersion.TIEBA_V22 -> {
        CommonRequest(
            BDUSS = bduss ?: SessionProviders.credential.getBduss(),
            _client_id = ClientUtils.clientId ?: RetrofitTiebaApi.randomClientId,
            _client_type = 2,
            _client_version = clientVersion.version,
            _os_version = "${Build.VERSION.SDK_INT}",
            _phone_imei = MobileInfoUtil.getIMEI(context),
            _timestamp = System.currentTimeMillis(),
            active_timestamp = ClientUtils.activeTimestamp,
            android_id = base64Encode(UIDUtil.getAndroidId("000")),
            brand = Build.BRAND,
            c3_aid = UIDUtil.getAid(),
            cmode = 1,
            cuid = CuidUtils.getNewCuid(),
            cuid_galaxy2 = CuidUtils.getNewCuid(),
            cuid_gid = "",
            event_day = SimpleDateFormat("yyyyMdd", Locale.getDefault()).format(
                Date(
                    System.currentTimeMillis()
                )
            ),
            extra = "",
            first_install_time = SessionProviders.clientConfig.appFirstInstallTime,
            framework_ver = "4220001",
            from = "1020031h",
            is_teenager = 0,
            last_update_time = SessionProviders.clientConfig.appLastUpdateTime,
            lego_lib_version = "3.0.0",
            model = Build.MODEL,
            net_type = 1,
            oaid = "",
            personalized_rec_switch = 1,
            pversion = "1.0.3",
            q_type = 0,
            sample_id = ClientUtils.sampleId,
            scr_dip = SessionProviders.deviceInfo.density.toDouble(),
            scr_h = getScreenHeight(),
            scr_w = getScreenWidth(),
            sdk_ver = "3.36.0",
            start_scheme = "",
            start_type = 1,
            stoken = stoken ?: SessionProviders.credential.getSToken(),
            swan_game_ver = "2035000",
            user_agent = getUserAgent("tieba/${clientVersion.version}"),
            z_id = SessionProviders.credential.getZId()
        )
    }

    ClientVersion.TIEBA_V12_POST -> {
        CommonRequest(
            BDUSS = bduss ?: SessionProviders.credential.getBduss(),
            _client_id = ClientUtils.clientId ?: RetrofitTiebaApi.randomClientId,
            _client_type = 2,
            _client_version = clientVersion.version,
            _os_version = "${Build.VERSION.SDK_INT}", // TODO
            _phone_imei = MobileInfoUtil.getIMEI(context),
            _timestamp = System.currentTimeMillis(),
            active_timestamp = ClientUtils.activeTimestamp,
            android_id = UIDUtil.getAndroidId("000"),
            applist = "",
            brand = Build.BRAND,
            c3_aid = UIDUtil.getAid(),
            cmode = 1,
            cuid = CuidUtils.getNewCuid(),
            cuid_galaxy2 = CuidUtils.getNewCuid(),
            cuid_gid = "",
            device_score = "${DeviceUtils.getDeviceScore()}",
            event_day = SimpleDateFormat("yyyyMdd", Locale.getDefault()).format(
                Date(
                    System.currentTimeMillis()
                )
            ),
            extra = "",
            first_install_time = SessionProviders.clientConfig.appFirstInstallTime,
            framework_ver = "3340042",
            from = "1020031h",
            is_teenager = 0,
            last_update_time = SessionProviders.clientConfig.appLastUpdateTime,
            lego_lib_version = "3.0.0",
            model = Build.MODEL,
            net_type = 1,
            oaid = SessionProviders.oaid.encodedOAID,
            personalized_rec_switch = 1,
            pversion = "1.0.3",
            q_type = 0,
            sample_id = ClientUtils.sampleId,
            scr_dip = SessionProviders.deviceInfo.density.toDouble(),
            scr_h = getScreenHeight(),
            scr_w = getScreenWidth(),
            sdk_ver = "2.34.0",
            start_scheme = "",
            start_type = 1,
            stoken = stoken ?: SessionProviders.credential.getSToken(),
            swan_game_ver = "1038000",
            tbs = tbs,
            user_agent = getUserAgent("tieba/${clientVersion.version}"),
            z_id = SessionProviders.credential.getZId()
        )
    }
}
