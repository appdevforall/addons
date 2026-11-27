package com.appdevforall.pair.plugin.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.appdevforall.pair.plugin.PairTooltips
import com.appdevforall.pair.plugin.data.SessionState
import com.appdevforall.pair.plugin.ui.components.ConnectingLine
import com.appdevforall.pair.plugin.ui.components.InviteCard
import com.appdevforall.pair.plugin.ui.components.LabeledSwitchRow
import com.appdevforall.pair.plugin.ui.components.OutOfSyncBanner
import com.appdevforall.pair.plugin.ui.components.PluginButtonOutlined
import com.appdevforall.pair.plugin.ui.components.PluginButtonText
import com.appdevforall.pair.plugin.ui.components.StatusDot
import com.appdevforall.pair.plugin.ui.components.longPressTooltip
import com.appdevforall.pair.plugin.ui.preview.PluginPreview
import com.appdevforall.pair.plugin.ui.preview.PreviewSamples
import com.appdevforall.pair.plugin.ui.preview.ScreenThemePreviews
import com.appdevforall.pair.plugin.ui.theme.LocalPluginDimens
import com.appdevforall.pair.plugin.ui.theme.LocalPluginTextStyles

@Composable
fun HostSessionScreen(
    session: SessionState,
    discoverable: Boolean,
    onIntent: (PairIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalPluginDimens.current
    val styles = LocalPluginTextStyles.current
    val address = session.localAddress ?: "—"
    val port = session.localPort ?: 0
    var inviteExpanded by remember { mutableStateOf(false) }
    val showInvite = session.peers.isEmpty() || inviteExpanded

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 112.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.padding(vertical = dimens.spaceMd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm),
            ) {
                StatusDot(color = MaterialTheme.colorScheme.primary, size = dimens.liveDotSize)
                Text(
                    text = "HOSTING",
                    style = styles.label.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }

            PluginButtonOutlined(
                text = "STOP SESSION",
                onClick = { onIntent(PairIntent.StopSession) },
                contentColor = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = dimens.spaceXl)
                    .longPressTooltip(PairTooltips.STOP_SESSION),
            )

            Spacer(Modifier.height(dimens.spaceXl))

            OutOfSyncBanner(
                visible = session.outOfSync,
                canResync = true,
                onResync = { onIntent(PairIntent.ForceResync) },
            )

            if (showInvite) {
                InviteCard(
                    address = address,
                    port = port,
                    token = session.localToken,
                    modifier = Modifier.padding(horizontal = dimens.spaceXl),
                )

                Spacer(Modifier.height(dimens.spaceLg))

                LabeledSwitchRow(
                    title = "Discoverable",
                    subtitle = "Nearby devices can join without scanning.",
                    checked = discoverable,
                    onCheckedChange = { onIntent(PairIntent.ToggleDiscoverable) },
                    modifier = Modifier
                        .padding(horizontal = dimens.spaceXl)
                        .longPressTooltip(PairTooltips.DISCOVERABLE),
                )
            } else {
                PluginButtonText(
                    text = "INVITE ANOTHER DEVICE",
                    onClick = { inviteExpanded = true },
                    modifier = Modifier
                        .padding(horizontal = dimens.spaceXl)
                        .longPressTooltip(PairTooltips.INVITE_CARD),
                )
            }

            Spacer(Modifier.height(dimens.spaceLg))

            LabeledSwitchRow(
                title = "Show others' cursors",
                subtitle = "Display each peer's caret in your editor.",
                checked = session.showPeerCursors,
                onCheckedChange = { onIntent(PairIntent.SetShowPeerCursors(it)) },
                modifier = Modifier
                    .padding(horizontal = dimens.spaceXl)
                    .longPressTooltip(PairTooltips.PEER_CURSORS),
            )

            Spacer(Modifier.height(dimens.spaceXl))

            AnimatedContent(
                targetState = session.peers.isEmpty(),
                transitionSpec = {
                    fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                },
                label = "host-peer-area",
            ) { isEmpty ->
                if (isEmpty) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ConnectingLine(
                            visible = true,
                            modifier = Modifier.padding(horizontal = dimens.spaceXl),
                        )
                        Spacer(Modifier.height(dimens.spaceMd))
                        Text(
                            text = "Waiting for someone to scan or connect…",
                            style = styles.bodyMuted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = dimens.spaceXl),
                        )
                        Spacer(Modifier.height(dimens.spaceXs))
                        Text(
                            text = "Share the code above.",
                            style = styles.small.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = dimens.spaceXl),
                        )
                    }
                } else {
                    PeerListSection(peers = session.peers)
                }
            }
        }
    }
}

@ScreenThemePreviews
@Composable
private fun HostSessionScreenPreview() {
    PluginPreview(contentPadding = 0.dp) {
        HostSessionScreen(session = PreviewSamples.hostSession, discoverable = true, onIntent = {})
    }
}

@ScreenThemePreviews
@Composable
private fun HostSessionScreenWaitingPreview() {
    PluginPreview(contentPadding = 0.dp) {
        HostSessionScreen(session = PreviewSamples.hostWaitingSession, discoverable = true, onIntent = {})
    }
}
