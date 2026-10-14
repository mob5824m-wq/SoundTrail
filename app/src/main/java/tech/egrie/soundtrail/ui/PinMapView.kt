package tech.egrie.soundtrail.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.egrie.soundtrail.data.GeoPoint
import tech.egrie.soundtrail.data.MusicPin
import tech.egrie.soundtrail.integrations.MusicProvider
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Fetches raster map tiles for [PinMapView]. Tiles are cached in memory only (a couple of
 * screens' worth), failures back off for a minute, and every request carries a descriptive
 * User-Agent per OpenStreetMap's tile policy. Nothing about pins or location is sent here:
 * the URL contains only the public map tile coordinates.
 */
private class TileFetcher(private val onLoaded: () -> Unit) {
    private val cache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
            size > 96
    }
    private val inFlight = Collections.synchronizedSet(mutableSetOf<String>())
    private val failedAt = Collections.synchronizedMap(mutableMapOf<String, Long>())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var closed = false

    fun tile(z: Int, x: Int, y: Int): ImageBitmap? = synchronized(cache) { cache[key(z, x, y)] }

    fun request(z: Int, x: Int, y: Int) {
        val side = 1 shl z
        val wrappedX = ((x % side) + side) % side
        if (y < 0 || y >= side) return
        val key = key(z, wrappedX, y)
        synchronized(failedAt) {
            failedAt[key]?.let { at -> if (System.currentTimeMillis() - at < 60_000L) return }
        }
        if (!inFlight.add(key)) return
        scope.launch {
            try {
                val bitmap = fetch(z, wrappedX, y)
                if (bitmap != null) {
                    synchronized(cache) { cache[key] = bitmap }
                    synchronized(failedAt) { failedAt.remove(key) }
                    withContext(Dispatchers.Main) { if (!closed) onLoaded() }
                } else {
                    synchronized(failedAt) { failedAt[key] = System.currentTimeMillis() }
                }
            } catch (_: Exception) {
                synchronized(failedAt) { failedAt[key] = System.currentTimeMillis() }
            } finally {
                inFlight.remove(key)
            }
        }
    }

    fun close() {
        closed = true
        scope.cancel()
    }

    private fun key(z: Int, x: Int, y: Int) = "$z/$x/$y"

    private fun fetch(z: Int, x: Int, y: Int): ImageBitmap? {
        val connection = (URL("https://basemaps.cartocdn.com/dark_all/$z/$x/$y.png")
            .openConnection() as HttpURLConnection)
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty(
                "User-Agent",
                "Soundtrail/1.0 (https://github.com/egrie-tech/Geotagged-Music-App)"
            )
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    // A tile is tens of kilobytes; anything past this is not a tile.
                    if (bytes.size() + read > 512 * 1024) return null
                    bytes.write(chunk, 0, read)
                }
            }
            val data = bytes.toByteArray()
            if (data.isEmpty()) null
            else BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap()
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * An interactive OpenStreetMap/CARTO map of the user's pins, drawn entirely on-device:
 * an off-screen Canvas composes cached raster tiles, then places provider-coloured pin
 * markers, the user's fix, and the selected-pin card on top.
 *
 * Gestures: drag to pan, pinch or double-tap to zoom (anchored under the finger), tap a
 * marker to select it, tap empty map to deselect, long-press to drop a new pin there.
 * Viewing the map does contact the tile server for the visible area — that is the one
 * network cost of an embedded map, and it is called out in the README.
 */
@Composable
internal fun PinMapView(
    pins: List<MusicPin>,
    userLocation: GeoPoint?,
    /** A pin chosen outside the map — a search result, or a row in the list — to open here. */
    focusPin: MusicPin? = null,
    onPlay: (MusicPin) -> Unit,
    onMap: (GeoPoint) -> Unit,
    onDelete: (MusicPin) -> Unit,
    onDropPin: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier
) {
    var viewSize by remember { mutableStateOf(Size.Zero) }
    var center by remember { mutableStateOf(GeoPoint(0.0, 0.0)) }
    var zoom by remember { mutableStateOf(MapMath.MIN_ZOOM) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var userMoved by remember { mutableStateOf(false) }
    var tileVersion by remember { mutableStateOf(0) }

    val fetcher = remember { TileFetcher { tileVersion++ } }
    DisposableEffect(Unit) { onDispose { fetcher.close() } }

    // Frame every pin the first time the view has a size, and re-frame when the set of
    // pins changes — until the user pans or zooms themselves.
    LaunchedEffect(viewSize.width, viewSize.height, pins, userLocation) {
        if (viewSize.width <= 0f || viewSize.height <= 0f || userMoved) return@LaunchedEffect
        val points = pins.map { it.point } + listOfNotNull(userLocation)
        val (fittedCenter, fittedZoom) = MapMath.fitTo(
            points, viewSize.width.toDouble(), viewSize.height.toDouble()
        )
        center = fittedCenter
        zoom = fittedZoom
    }

    // A pin picked somewhere else (a search hit, a row in the list) is brought into view with
    // its card open, rather than leaving the listener to hunt for it on the map.
    LaunchedEffect(focusPin?.id) {
        val pin = focusPin ?: return@LaunchedEffect
        center = pin.point
        zoom = MapMath.clampZoom(maxOf(zoom, FOCUS_ZOOM))
        selectedId = pin.id
        userMoved = true
    }

    // A deleted (or externally removed) pin must not stay selected.
    LaunchedEffect(pins) {
        if (selectedId != null && pins.none { it.id == selectedId }) selectedId = null
    }

    val selected = pins.firstOrNull { it.id == selectedId }

    Box(modifier) {
        Canvas(
            Modifier.fillMaxSize().onSizeChanged { viewSize = Size(it.width.toFloat(), it.height.toFloat()) }
                .pointerInput(viewSize) {
                    detectTransformGestures { centroid, pan, zoomChange, _ ->
                        if (viewSize.width <= 0f) return@detectTransformGestures
                        val width = viewSize.width.toDouble()
                        val height = viewSize.height.toDouble()
                        val currentZoom = zoom
                        // The geo point under the fingers before the gesture, so zooming
                        // keeps that exact spot under them.
                        val before = MapMath.worldPixel(center.latitude, center.longitude, currentZoom)
                        val anchor = MapMath.pointAt(
                            before.first + (centroid.x - width / 2.0),
                            before.second + (centroid.y - height / 2.0),
                            currentZoom
                        )
                        val newZoom = MapMath.clampZoom(currentZoom + log2(zoomChange.toDouble().coerceAtLeast(0.05)))
                        val anchorNow = MapMath.worldPixel(anchor.latitude, anchor.longitude, newZoom)
                        var newWorldX = anchorNow.first - centroid.x + width / 2.0
                        val newWorldY = anchorNow.second - centroid.y + height / 2.0
                        // Content follows the finger, so the center slides the other way.
                        newWorldX -= pan.x.toDouble()
                        val moved = MapMath.pointAt(newWorldX, newWorldY - pan.y.toDouble(), newZoom)
                        center = GeoPoint(MapMath.clampLatitude(moved.latitude), moved.longitude)
                        zoom = newZoom
                        userMoved = true
                    }
                }
                .pointerInput(viewSize) {
                    fun worldAt(offset: Offset): Pair<Double, Double> {
                        val width = viewSize.width.toDouble()
                        val height = viewSize.height.toDouble()
                        val world = MapMath.worldPixel(center.latitude, center.longitude, zoom)
                        return world.first + (offset.x - width / 2.0) to
                            world.second + (offset.y - height / 2.0)
                    }

                    fun zoomAt(offset: Offset, steps: Double) {
                        val width = viewSize.width.toDouble()
                        val height = viewSize.height.toDouble()
                        val before = MapMath.worldPixel(center.latitude, center.longitude, zoom)
                        val anchor = MapMath.pointAt(
                            before.first + (offset.x - width / 2.0),
                            before.second + (offset.y - height / 2.0), zoom
                        )
                        val newZoom = MapMath.clampZoom(zoom + steps)
                        val anchorNow = MapMath.worldPixel(anchor.latitude, anchor.longitude, newZoom)
                        val moved = MapMath.pointAt(
                            anchorNow.first - offset.x + width / 2.0,
                            anchorNow.second - offset.y + height / 2.0, newZoom
                        )
                        center = GeoPoint(MapMath.clampLatitude(moved.latitude), moved.longitude)
                        zoom = newZoom
                        userMoved = true
                    }

                    detectTapGestures(
                        onTap = { offset ->
                            if (viewSize.width <= 0f) return@detectTapGestures
                            val width = viewSize.width
                            val height = viewSize.height
                            val hitRadius = 34.dp.toPx()
                            val headOffset = 12.dp.toPx() * 1.8f
                            val hit = pins.firstOrNull { pin ->
                                val (mx, my) = MapMath.screenOffset(
                                    pin.point, center, zoom, width.toDouble(), height.toDouble()
                                )
                                // Markers are drawn tip-at-(mx,my); the round head sits above it.
                                val headY = my - headOffset
                                abs(mx - offset.x) <= hitRadius && abs(headY - offset.y) <= hitRadius
                            }
                            selectedId = hit?.id
                        },
                        onDoubleTap = { offset ->
                            if (viewSize.width <= 0f) return@detectTapGestures
                            zoomAt(offset, 1.0)
                        },
                        onLongPress = { offset ->
                            if (viewSize.width <= 0f) return@detectTapGestures
                            val (wx, wy) = worldAt(offset)
                            val geo = MapMath.pointAt(wx, wy, zoom)
                            val point = GeoPoint(MapMath.clampLatitude(geo.latitude), geo.longitude)
                            if (point.isValid()) onDropPin(point)
                        }
                    )
                }
        ) {
            val width = size.width.toDouble()
            val height = size.height.toDouble()
            if (width <= 0.0 || height <= 0.0) return@Canvas
            drawRect(Palette.background)

            // Tiles, scaled from the integer zoom they are rendered at.
            val range = MapMath.visibleTiles(center, zoom, width, height)
            if (!range.isEmpty) {
                @Suppress("UNUSED_EXPRESSION") tileVersion // read for redraws when a tile lands
                val factor = 2.0.pow(zoom - range.zoom)
                val tileSize = MapMath.TILE_SIZE * factor
                val centerPx = MapMath.worldPixel(center.latitude, center.longitude, zoom)
                val side = 1 shl range.zoom
                for (tileY in range.ys) {
                    for (tileX in range.xs) {
                        val wrappedX = ((tileX % side) + side) % side
                        var image = fetcher.tile(range.zoom, wrappedX, tileY)
                        if (image == null) {
                            fetcher.request(range.zoom, wrappedX, tileY)
                        }
                        val offsetX = MapMath.wrapDelta(
                            tileX * tileSize - centerPx.first, MapMath.worldSize(zoom)
                        ) + width / 2.0
                        val offsetY = tileY * tileSize - centerPx.second + height / 2.0
                        if (offsetX > width || offsetX + tileSize < 0.0 ||
                            offsetY > height || offsetY + tileSize < 0.0
                        ) continue
                        if (image != null) {
                            drawImage(
                                image = image,
                                dstOffset = IntOffset(offsetX.roundToInt(), offsetY.roundToInt()),
                                dstSize = IntSize(
                                    tileSize.roundToInt().coerceAtLeast(1),
                                    tileSize.roundToInt().coerceAtLeast(1)
                                )
                            )
                        }
                    }
                }
            }

            // The user's own fix, when location permission has produced one.
            userLocation?.let { fix ->
                val (ux, uy) = MapMath.screenOffset(fix, center, zoom, width, height)
                if (ux in -20.0..width + 20.0 && uy in -20.0..height + 20.0) {
                    drawCircle(Palette.primary.copy(alpha = .35f), radius = 13.dp.toPx(),
                        center = Offset(ux.toFloat(), uy.toFloat()))
                    drawCircle(Palette.primary, radius = 7.dp.toPx(),
                        center = Offset(ux.toFloat(), uy.toFloat()))
                    drawCircle(Palette.background, radius = 2.5.dp.toPx(),
                        center = Offset(ux.toFloat(), uy.toFloat()))
                }
            }

            // Pins: selected one last so it never hides under a neighbour.
            val ordered = if (selected == null) pins else pins.filterNot { it.id == selected.id } + selected
            ordered.forEach { pin ->
                val (mx, my) = MapMath.screenOffset(pin.point, center, zoom, width, height)
                if (mx < -60.0 || mx > width + 60.0 || my < -60.0 || my > height + 60.0) return@forEach
                drawPinMarker(
                    x = mx.toFloat(), y = my.toFloat(),
                    color = pinColor(pin.provider),
                    selected = pin.id == selectedId
                )
            }
        }

        // Zoom controls — pinch works, but taps must too.
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MapControl("+", "Zoom in") {
                if (viewSize.width > 0f) {
                    zoom = MapMath.clampZoom(zoom + 1.0); userMoved = true
                }
            }
            Spacer(Modifier.height(6.dp))
            MapControl("−", "Zoom out") {
                if (viewSize.width > 0f) {
                    zoom = MapMath.clampZoom(zoom - 1.0); userMoved = true
                }
            }
        }

        // The two things a map is for, one tap away: where am I, and put a pin here. Long-press
        // still drops a pin exactly where the finger is; this drops one where you are looking.
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MapControl("Me", "Centre the map on my location") {
                val fix = userLocation ?: return@MapControl
                center = fix
                zoom = MapMath.clampZoom(maxOf(zoom, FOCUS_ZOOM))
                userMoved = true
            }
            MapControl("Pin", "Drop a soundspot at the centre of the map") {
                if (viewSize.width > 0f) onDropPin(center)
            }
        }

        if (selected != null) {
            Column(
                Modifier.align(Alignment.BottomCenter).padding(horizontal = 4.dp, vertical = 10.dp)
                    .fillMaxWidth().heightIn(max = 330.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                PinCard(
                    selected,
                    userLocation?.distanceTo(selected.point),
                    onPlay = { onPlay(selected) },
                    onMap = { onMap(selected.point) },
                    onDelete = { onDelete(selected) }
                )
            }
        } else {
            Column(
                Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (pins.isEmpty()) {
                    Text(
                        "No soundspots to show yet — add one from the list, or long-press anywhere on the map.",
                        color = Palette.secondary, style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(
                        "Tap a pin to play · long-press to drop a new one",
                        color = Palette.secondary, style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        Text(
            "© OpenStreetMap · © CARTO",
            color = Palette.text.copy(alpha = .65f), fontSize = 10.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopStart)
                .padding(start = 4.dp, top = 10.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Palette.background.copy(alpha = .55f))
                .padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun MapControl(label: String, contentDescription: String, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(12.dp))
            .background(Palette.surface.copy(alpha = .92f))
            .border(1.dp, Palette.border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Palette.text, style = MaterialTheme.typography.titleMedium)
    }
}

/** How close the map is when it is aimed at one soundspot. */
private const val FOCUS_ZOOM = 15.0

/** Provider colours match the pills used everywhere else in Soundtrail. */
private fun pinColor(provider: MusicProvider): Color = when (provider) {
    MusicProvider.SPOTIFY -> Palette.spotify
    MusicProvider.YOUTUBE -> Palette.youtube
    MusicProvider.YOUTUBE_MUSIC -> Palette.youtube
    MusicProvider.SOUNDCLOUD -> Palette.soundcloud
    MusicProvider.JELLYFIN -> Palette.jellyfin
}

/**
 * A map marker: round head, pointed tip at (x, y) so the point lands on the exact spot —
 * with a halo behind the selected one.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPinMarker(
    x: Float, y: Float, color: Color, selected: Boolean
) {
    val radius = (if (selected) 15.dp else 12.dp).toPx()
    val headY = y - radius * 1.8f
    if (selected) {
        drawCircle(color.copy(alpha = .28f), radius = radius * 2.2f, center = Offset(x, headY))
    }
    val stem = Path().apply {
        moveTo(x, y)
        lineTo(x - radius * 0.68f, headY - radius * 0.5f)
        lineTo(x + radius * 0.68f, headY - radius * 0.5f)
        close()
    }
    drawPath(stem, color)
    drawCircle(color, radius = radius, center = Offset(x, headY))
    drawCircle(Palette.background, radius = radius * 0.42f, center = Offset(x, headY))
}
