package com.dezzy.music.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dezzy.music.R

/**
 * Banderas propias en XML (res/drawable/flag_xx.xml, generadas por flags_gen.py).
 * Sustituyen a los emojis de bandera, que cada teléfono pinta distinto o no tiene.
 * Si un país no tiene dibujo, sale el globo de «Mundial» (flag_zz).
 */
@DrawableRes
fun flagRes(code: String?): Int = when (code?.uppercase()) {
    "AE" -> R.drawable.flag_ae
    "AR" -> R.drawable.flag_ar
    "AT" -> R.drawable.flag_at
    "AU" -> R.drawable.flag_au
    "BE" -> R.drawable.flag_be
    "BG" -> R.drawable.flag_bg
    "BO" -> R.drawable.flag_bo
    "BR" -> R.drawable.flag_br
    "BY" -> R.drawable.flag_by
    "CA" -> R.drawable.flag_ca
    "CH" -> R.drawable.flag_ch
    "CL" -> R.drawable.flag_cl
    "CO" -> R.drawable.flag_co
    "CR" -> R.drawable.flag_cr
    "CY" -> R.drawable.flag_cy
    "CZ" -> R.drawable.flag_cz
    "DE" -> R.drawable.flag_de
    "DK" -> R.drawable.flag_dk
    "DO" -> R.drawable.flag_do
    "EC" -> R.drawable.flag_ec
    "EE" -> R.drawable.flag_ee
    "EG" -> R.drawable.flag_eg
    "ES" -> R.drawable.flag_es
    "FI" -> R.drawable.flag_fi
    "FR" -> R.drawable.flag_fr
    "GB" -> R.drawable.flag_gb
    "GR" -> R.drawable.flag_gr
    "GT" -> R.drawable.flag_gt
    "HK" -> R.drawable.flag_hk
    "HN" -> R.drawable.flag_hn
    "HU" -> R.drawable.flag_hu
    "ID" -> R.drawable.flag_id
    "IE" -> R.drawable.flag_ie
    "IL" -> R.drawable.flag_il
    "IN" -> R.drawable.flag_in
    "IS" -> R.drawable.flag_is
    "IT" -> R.drawable.flag_it
    "JP" -> R.drawable.flag_jp
    "KR" -> R.drawable.flag_kr
    "KZ" -> R.drawable.flag_kz
    "LT" -> R.drawable.flag_lt
    "LU" -> R.drawable.flag_lu
    "LV" -> R.drawable.flag_lv
    "MA" -> R.drawable.flag_ma
    "MX" -> R.drawable.flag_mx
    "MY" -> R.drawable.flag_my
    "NG" -> R.drawable.flag_ng
    "NI" -> R.drawable.flag_ni
    "NL" -> R.drawable.flag_nl
    "NO" -> R.drawable.flag_no
    "NZ" -> R.drawable.flag_nz
    "PA" -> R.drawable.flag_pa
    "PE" -> R.drawable.flag_pe
    "PH" -> R.drawable.flag_ph
    "PK" -> R.drawable.flag_pk
    "PL" -> R.drawable.flag_pl
    "PR" -> R.drawable.flag_pr
    "PT" -> R.drawable.flag_pt
    "PY" -> R.drawable.flag_py
    "RO" -> R.drawable.flag_ro
    "RU" -> R.drawable.flag_ru
    "SA" -> R.drawable.flag_sa
    "SE" -> R.drawable.flag_se
    "SG" -> R.drawable.flag_sg
    "SK" -> R.drawable.flag_sk
    "SV" -> R.drawable.flag_sv
    "TH" -> R.drawable.flag_th
    "TR" -> R.drawable.flag_tr
    "TW" -> R.drawable.flag_tw
    "UA" -> R.drawable.flag_ua
    "US" -> R.drawable.flag_us
    "UY" -> R.drawable.flag_uy
    "VE" -> R.drawable.flag_ve
    "VN" -> R.drawable.flag_vn
    "ZA" -> R.drawable.flag_za
    else -> R.drawable.flag_zz
}

/** Bandera 3:2 de un país por su código ISO («MX»). Decorativa: el nombre del país va al lado. */
@Composable
fun CountryFlag(
    code: String?,
    modifier: Modifier = Modifier,
    height: Dp = 16.dp,
    contentDescription: String? = null,
) {
    Image(
        painter = painterResource(flagRes(code)),
        contentDescription = contentDescription,
        modifier = modifier.size(width = height * 1.5f, height = height),
    )
}
