package com.appdevforall.pair.plugin.ui.main

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
import com.appdevforall.pair.plugin.ui.preview.PluginPreview
import com.appdevforall.pair.plugin.ui.preview.PreviewSamples
import com.appdevforall.pair.plugin.ui.preview.ScreenThemePreviews
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.appdevforall.pair.plugin.PairTooltips
import com.appdevforall.pair.plugin.data.SessionState
import com.appdevforall.pair.plugin.ui.components.ConnectingLine
import com.appdevforall.pair.plugin.ui.components.LabeledSwitchRow
import com.appdevforall.pair.plugin.ui.components.OutOfSyncBanner
import com.appdevforall.pair.plugin.ui.components.PluginButtonOutlined
import com.appdevforall.pair.plugin.ui.components.ProjectTransferCard
import com.appdevforall.pair.plugin.ui.components.StatusDot
import com.appdevforall.pair.plugin.ui.components.longPressTooltip
import com.appdevforall.pair.plugin.ui.theme.LocalPluginDimens
import com.appdevforall.pair.plugin.ui.theme.LocalPluginTextStyles

@Composable
fun GuestSessionScreen(
    session: SessionState,
    onIntent: (PairIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalPluginDimens.current
    val styles = LocalPluginTextStyles.current
    val connecting = session.peers.isEmpty() && session.lastError == null

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 112.dp),
        ) {
            ConnectingLine(visible = connecting)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = dimens.spaceXl, vertical = dimens.spaceMd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm),
            ) {
                StatusDot(color = MaterialTheme.colorScheme.primary)
                Text(
                    text = "CONNECTED TO",
                    style = styles.label.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
                Text(
                    text = session.remoteAddress ?: "—",
                    style = styles.mono.copy(color = MaterialTheme.colorScheme.onSurface),
                )
            }

            PluginButtonOutlined(
                text = "DISCONNECT",
                onClick = { onIntent(PairIntent.Disconnect) },
                contentColor = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = dimens.spaceXl)
                    .longPressTooltip(PairTooltips.DISCONNECT),
            )

            OutOfSyncBanner(
                visible = session.outOfSync,
                canResync = false,
                onResync = {},
            )

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

            Spacer(Modifier.height(dimens.spaceLg))

            ProjectTransferCard(
                received = session.transferReceived,
                total = session.transferTotal,
                onPull = { onIntent(PairIntent.PullProject) },
                modifier = Modifier.padding(horizontal = dimens.spaceXl),
            )

            Spacer(Modifier.height(dimens.spaceLg))

            PeerListSection(peers = session.peers)

            if (session.lastError != null) {
                Spacer(Modifier.height(dimens.spaceMd))
                Text(
                    text = session.lastError,
                    style = styles.small.copy(color = MaterialTheme.colorScheme.error),
                    modifier = Modifier.padding(horizontal = dimens.spaceXl),
                )
            }
        }
    }
}

@ScreenThemePreviews
@Composable
private fun GuestSessionScreenPreview() {
    PluginPreview(contentPadding = 0.dp) {
        GuestSessionScreen(
            session = PreviewSamples.guestSession,
            onIntent = {},
        )
    }
}
