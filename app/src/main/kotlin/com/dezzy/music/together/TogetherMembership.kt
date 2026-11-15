/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.together

import com.dezzy.music.listentogether.ListenTogetherClient
import com.dezzy.music.listentogether.RoomRole

/** Relación de este dispositivo con una sala. */
enum class TogetherRoomMembership { NONE, HOST, GUEST }

/** Sala en la que está este dispositivo ahora mismo. */
data class TogetherCurrentRoom(
    val code: String,
    val membership: TogetherRoomMembership,
)

/**
 * Sala actual de este dispositivo (código en mayúsculas) y su rol, o null si no está en ninguna.
 *
 * Sirve para no volver a "unirse" a la propia sala: hacerlo por el mismo socket borraba la
 * sesión de anfitrión y la sala se quedaba sin host. Mira primero el estado del servicio y,
 * si no dice nada (p. ej. reconectando tras un corte), el cliente de Listen Together.
 */
fun currentTogetherRoom(state: TogetherSessionState?): TogetherCurrentRoom? {
    when (state) {
        is TogetherSessionState.HostingOnline ->
            return TogetherCurrentRoom(state.code.uppercase(), TogetherRoomMembership.HOST)
        is TogetherSessionState.Hosting ->
            return TogetherCurrentRoom(state.sessionId.uppercase(), TogetherRoomMembership.HOST)
        is TogetherSessionState.Joined ->
            return TogetherCurrentRoom(
                state.sessionId.uppercase(),
                if (state.role is TogetherRole.Host) TogetherRoomMembership.HOST else TogetherRoomMembership.GUEST,
            )
        is TogetherSessionState.JoiningOnline ->
            return TogetherCurrentRoom(state.code.uppercase(), TogetherRoomMembership.GUEST)
        else -> Unit
    }
    val client = ListenTogetherClient.getInstance() ?: return null
    val code = client.roomState.value?.roomCode?.takeIf { it.isNotBlank() } ?: return null
    val membership = when (client.role.value) {
        RoomRole.HOST -> TogetherRoomMembership.HOST
        RoomRole.GUEST -> TogetherRoomMembership.GUEST
        RoomRole.NONE -> return null
    }
    return TogetherCurrentRoom(code.uppercase(), membership)
}

/** Rol de este dispositivo en la sala [code] (comparación sin distinguir mayúsculas). */
fun TogetherCurrentRoom?.membershipIn(code: String): TogetherRoomMembership =
    if (this != null && this.code.equals(code.trim(), ignoreCase = true)) membership else TogetherRoomMembership.NONE
