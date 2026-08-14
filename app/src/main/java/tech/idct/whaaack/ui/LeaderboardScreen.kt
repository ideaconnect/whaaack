package tech.idct.whaaack.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.idct.whaaack.UiState
import tech.idct.whaaack.data.BoardRow
import tech.idct.whaaack.data.BoardScope
import tech.idct.whaaack.data.LeaderboardRepository
import tech.idct.whaaack.ui.theme.AccentDark
import tech.idct.whaaack.ui.theme.AccentInk
import tech.idct.whaaack.ui.theme.AccentLight
import tech.idct.whaaack.ui.theme.Cream
import tech.idct.whaaack.ui.theme.Hairline
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

@Composable
fun LeaderboardScreen(
    state: UiState,
    onBack: () -> Unit,
    onScopeChange: (BoardScope) -> Unit,
    onSignIn: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        ScreenHeader("Leaderboard", onBack)

        Spacer(Modifier.height(16.dp))
        SegmentedTabs(
            options = BoardScope.entries.map { it.label },
            selectedIndex = BoardScope.entries.indexOf(state.boardScope),
            onSelect = { onScopeChange(BoardScope.entries[it]) },
        )

        // Which week "this week" actually is. The board is cut server-side at Monday 00:00
        // UTC, which is 02:00 Monday for a Polish player in summer and the *previous* Sunday
        // evening for most of the Americas — so a board that silently emptied overnight looked
        // like a bug rather than a reset. Naming the window is the whole fix.
        if (state.boardScope == BoardScope.WEEKLY) {
            Spacer(Modifier.height(10.dp))
            Text(
                remember(state.boardScope) { currentUtcWeekLabel() },
                color = Color(0x8AFFF3E6),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(14.dp))
        Box(Modifier.weight(1f)) {
            when {
                state.boardLoading && state.board.isEmpty() ->
                    CenteredNote("Loading the orchard rankings…")

                state.boardError != null -> CenteredNote(state.boardError)

                state.board.isEmpty() -> CenteredNote(
                    "No ranked runs yet.\nBe the first name on the board."
                )

                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.board, key = { it.userId + it.rank }) { row ->
                        BoardRowItem(row, isMe = row.userId == state.player?.userId)
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0x80140A1A))
                .border(1.dp, Color(0x33FFF3E6), RoundedCornerShape(18.dp))
                .then(if (state.signedIn) Modifier else Modifier.clickableOnce(true, onSignIn))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val standing = state.standing
            Text(
                standing?.let { "#${it.rank}" } ?: "—",
                color = AccentLight,
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
            )
            Text(
                if (state.signedIn) "${state.player?.displayName} (you)" else "Sign in to rank",
                color = Cream,
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.weight(1f),
            )
            Text(
                standing?.let { LeaderboardRepository.formatScore(it.millis) }
                    ?: LeaderboardRepository.formatScore(state.prefs.localBestMillis),
                color = Cream,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
            )
        }
    }
}

@Composable
private fun BoardRowItem(row: BoardRow, isMe: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                when {
                    isMe -> Color(0x4DFFC97A)
                    row.isTopThree -> Color(0xA846203A)
                    else -> Color(0x99140A1A)
                }
            )
            .border(
                1.dp,
                if (row.isTopThree) Color(0x42FFF3E6) else Hairline,
                RoundedCornerShape(18.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (row.isTopThree) Brush.linearGradient(listOf(AccentLight, AccentDark))
                    else Brush.linearGradient(listOf(Color(0x1AFFF3E6), Color(0x1AFFF3E6)))
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                row.rank.toString(),
                color = if (row.isTopThree) AccentInk else Color(0xB3FFF3E6),
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.displayName, color = Cream, fontSize = 14.sp, fontWeight = FontWeight.Black)
            Text(
                row.achievedAt?.take(10)?.let { "ranked · $it" } ?: "ranked",
                color = Color(0x80FFF3E6),
                fontSize = 11.sp,
            )
        }
        Text(
            LeaderboardRepository.formatScore(row.millis),
            color = AccentLight,
            fontSize = 16.sp,
            fontWeight = FontWeight.Black,
        )
    }
}

@Composable
private fun CenteredNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = Color(0x99FFF3E6),
            fontSize = 14.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * "Mon 10 - Sun 16 Aug, UTC" for the week the server is currently counting.
 *
 * Mirrors `public.current_week_start()`, which is
 * `date_trunc('week', now() at time zone 'utc') at time zone 'utc'` — Postgres truncates to
 * the ISO week, so that is Monday 00:00 UTC, and the window runs to the next Monday 00:00 UTC
 * exclusive. Verified against the live database, including under hostile session timezones:
 * the instant is the same everywhere, only its rendering differs.
 *
 * Computed in UTC here for exactly that reason — using the device's zone would name a
 * different week for anyone east of London on a Sunday night.
 */
private fun currentUtcWeekLabel(): String = utcWeekLabel(LocalDate.now(ZoneOffset.UTC))

/** The formatting half, split out from the clock so it can be tested at the awkward dates. */
internal fun utcWeekLabel(today: LocalDate): String {
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val sunday = monday.plusDays(6)
    val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.US)
    val day = DateTimeFormatter.ofPattern("d", Locale.US)
    // Drop the repeated month when the week does not straddle one: "10 - 16 Aug", not
    // "10 Aug - 16 Aug".
    val from = if (monday.month == sunday.month) monday.format(day) else monday.format(dayMonth)
    return "Mon $from - Sun ${sunday.format(dayMonth)}, UTC"
}
