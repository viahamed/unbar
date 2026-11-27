package com.breakthebarriers.unbar.ui.screens

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breakthebarriers.unbar.BuildConfig
import com.breakthebarriers.unbar.R
import com.breakthebarriers.unbar.model.AppThemeMode
import com.breakthebarriers.unbar.ui.theme.HeroCardRadius
import com.breakthebarriers.unbar.ui.theme.InnerPanelRadius
import com.breakthebarriers.unbar.ui.theme.OuterCardRadius
import com.breakthebarriers.unbar.ui.theme.UnbarDarkBg
import com.breakthebarriers.unbar.ui.theme.UnbarHeroBlue
import com.breakthebarriers.unbar.ui.theme.UnbarOrange
import kotlinx.coroutines.delay

// (1.0.3) About redesign — the user-approved final mockup ("F"):
//  1. Blue hero card (radius 30, like the Home status card): app-icon
//     tile | thin divider | Break The Barriers tile, the "Unbar"
//     wordmark (white "Un", orange "bar" #FF9A55 for contrast on the blue hero), "by Break The Barriers",
//     the tagline, and two chips: "App v<versionName>" (read at
//     runtime) and "Soar core v0.2.5".
//  2. Links card: Website, Telegram channel, Source code — icon tile +
//     title + subtitle + chevron, with dividers.
//  3. Donation card, collapsed by default (TutorialAccordion style):
//     orange heart + bold title + muted chevron; expanded: the note,
//     then USDT · BEP20 and TON addresses in full, monospace, in 8dp
//     inner boxes with a neutral square copy button. Only the heart is
//     orange.
// Nested radius: 24dp cards / 16dp padding / 8dp inner. The old
// license line ("Licensed under Apache License 2.0", which did not
// match the repo's GPL-3.0 LICENSE) and the Soar link are no longer
// shown here.
private const val WEBSITE_URL = "https://unbar.cc"
private const val TELEGRAM_CHANNEL_URL = "https://t.me/Unbarapp"
private const val SOURCE_URL = "https://github.com/viahamed/unbar"
private const val USDT_BEP20_ADDRESS = "0x46f872b46BFa471607ad0F0042bdFFCB2B9c702b"
private const val TON_ADDRESS = "UQBdKaKX61k56IaJMbjTTMvXd7fskpbJnzGUPlQAyZT6RJn5"

private val HeroTileRadius = 20.dp
private val HeroTileSize = 72.dp

@Composable
fun AboutScreen(themeMode: AppThemeMode) {
    val isDark = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        AboutHeroCard(context = context, isDark = isDark)
        AboutLinksCard(context = context)
        AboutDonationCard()
        Spacer(modifier = Modifier.height(96.dp))
    }
}

/**
 * Resources for the IN-APP theme, not the system one: the app's own
 * Light/Dark choice does not change the `-night` resource qualifier, so
 * drawable-night/ variants (btb_logo, about_app_icon) are resolved from
 * a configuration context with the matching night bit.
 */
@Composable
private fun themedVectorPainter(context: Context, isDark: Boolean, @DrawableRes resId: Int): Painter {
    val res = remember(context, isDark) {
        val cfg = Configuration(context.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (isDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        context.createConfigurationContext(cfg).resources
    }
    val vector = remember(res, resId) { ImageVector.vectorResource(null, res, resId) }
    return rememberVectorPainter(vector)
}

private fun appVersionName(context: Context): String {
    return runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: BuildConfig.VERSION_NAME
}

@Composable
private fun AboutHeroCard(context: Context, isDark: Boolean) {
    val versionName = remember(context) { appVersionName(context) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(HeroCardRadius))
            .background(UnbarHeroBlue)
    ) {
        // Soft decorative circle, bottom-end (mockup: 200dp, white 6%).
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 40.dp, y = 70.dp)
                .size(200.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.06f))
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Logos keep their order in RTL too (app first, then BTB).
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HeroTile(background = if (isDark) UnbarDarkBg else Color.White) {
                        Image(
                            painter = themedVectorPainter(context, isDark, R.drawable.about_app_icon),
                            contentDescription = stringResource(R.string.app_name),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(44.dp)
                            .background(Color.White.copy(alpha = 0.25f))
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    HeroTile(background = if (isDark) UnbarDarkBg else Color.White) {
                        Image(
                            painter = themedVectorPainter(context, isDark, R.drawable.btb_logo),
                            contentDescription = "Break The Barriers",
                            modifier = Modifier
                                .height(58.dp)
                                .aspectRatio(538f / 734f),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Image(
                painter = painterResource(R.drawable.unbar_wordmark_hero),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier
                    .height(30.dp)
                    .aspectRatio(853f / 232f),
                contentScale = ContentScale.Fit
            )
            Text(
                text = stringResource(R.string.about_by_btb),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.about_line_2),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 24.sp),
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.95f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(14.dp))
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HeroChip(stringResource(R.string.about_chip_app, versionName))
                    HeroChip(stringResource(R.string.about_chip_core))
                    HeroChip(stringResource(R.string.about_chip_license))
                }
            }
        }
    }
}

@Composable
private fun HeroTile(background: Color, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(HeroTileSize)
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(HeroTileRadius))
            .clip(RoundedCornerShape(HeroTileRadius))
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

// Capsule pill: radius 11 (user rule), centred text.
@Composable
private fun HeroChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            fontWeight = FontWeight.Medium,
            color = Color.White,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun AboutCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(OuterCardRadius))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        content()
    }
}

@Composable
private fun AboutLinksCard(context: Context) {
    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
    AboutCard {
        AboutLinkRow(
            icon = { Icon(Icons.Rounded.Language, contentDescription = null, modifier = Modifier.size(20.dp)) },
            title = stringResource(R.string.about_link_website),
            subtitle = "unbar.cc",
            onClick = { open(WEBSITE_URL) }
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline)
        AboutLinkRow(
            icon = {
                Icon(
                    painterResource(R.drawable.ic_about_telegram),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            title = stringResource(R.string.about_link_telegram),
            subtitle = "@Unbarapp",
            onClick = { open(TELEGRAM_CHANNEL_URL) }
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline)
        AboutLinkRow(
            icon = {
                Icon(
                    painterResource(R.drawable.ic_about_github),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            title = stringResource(R.string.about_link_source),
            subtitle = "github.com/viahamed/unbar",
            onClick = { open(SOURCE_URL) }
        )
    }
}

@Composable
private fun AboutLinkRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(InnerPanelRadius))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(InnerPanelRadius))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.primary
            ) {
                icon()
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            // Handles/URLs are Latin: shown LTR, but start-aligned in
            // the screen's direction (right in Persian), like the mockup.
            Text(
                text = "\u2066$subtitle\u2069",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun AboutDonationCard() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    AboutCard(modifier = Modifier.animateContentSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Rounded.Favorite,
                contentDescription = null,
                tint = UnbarOrange,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = stringResource(R.string.donate_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Icon(
                if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
        if (expanded) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.donate_text),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 22.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            DonationAddress(label = stringResource(R.string.donate_usdt_bep20), address = USDT_BEP20_ADDRESS)
            DonationAddress(label = stringResource(R.string.donate_ton), address = TON_ADDRESS)
        }
    }
}

@Composable
private fun DonationAddress(label: String, address: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500L)
            copied = false
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = label,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Start
    )
    Spacer(modifier = Modifier.height(6.dp))
    // Address + copy button stay LTR in Persian too (the address is
    // Latin; the copy button sits after it, as in the mockup).
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(InnerPanelRadius))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(InnerPanelRadius))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable {
                        clipboard.setText(AnnotatedString(address))
                        copied = true
                        // Android 13+ shows its own clipboard
                        // confirmation; older versions get a toast.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(R.string.copy),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
