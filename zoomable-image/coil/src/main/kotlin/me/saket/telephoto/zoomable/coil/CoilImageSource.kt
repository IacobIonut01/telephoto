@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "CANNOT_OVERRIDE_INVISIBLE_MEMBER")

package me.saket.telephoto.zoomable.coil

import android.content.ContentResolver
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.decode.DataSource
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import coil.size.Dimension
import coil.size.SizeResolver
import coil.transition.CrossfadeTransition
import com.google.accompanist.drawablepainter.DrawablePainter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import me.saket.telephoto.subsamplingimage.ImageBitmapOptions
import me.saket.telephoto.subsamplingimage.SubSamplingImageSource
import me.saket.telephoto.subsamplingimage.asAssetPathOrNull
import me.saket.telephoto.zoomable.ZoomableImageSource
import me.saket.telephoto.zoomable.ZoomableImageSource.ResolveResult
import me.saket.telephoto.zoomable.coil.Resolver.ImageSourceFactory
import me.saket.telephoto.zoomable.internal.RememberWorker
import me.saket.telephoto.zoomable.internal.copy
import okio.Path.Companion.toPath
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import coil.size.Size as CoilSize

internal class CoilImageSource(
  private val model: Any?,
  private val imageLoader: ImageLoader,
) : ZoomableImageSource {

  @Composable
  override fun resolve(canvasSize: Flow<Size>): ResolveResult {
    val context = LocalContext.current
    val resolver = remember(this) {
      Resolver(
        request = model as? ImageRequest
          ?: ImageRequest.Builder(context)
            .data(model)
            .build(),
        imageLoader = imageLoader,
        sizeResolver = { canvasSize.first().toCoilSize() }
      )
    }
    return resolver.resolved
  }

  private fun Size.toCoilSize() = CoilSize(
    width = if (width.isFinite()) Dimension(width.roundToInt()) else Dimension.Undefined,
    height = if (height.isFinite()) Dimension(height.roundToInt()) else Dimension.Undefined
  )
}

internal class Resolver(
  internal val request: ImageRequest,
  internal val imageLoader: ImageLoader,
  private val sizeResolver: SizeResolver,
) : RememberWorker() {

  internal var resolved: ResolveResult by mutableStateOf(
    ResolveResult(delegate = null)
  )

  override suspend fun work() {
    val result = imageLoader.execute(
      request.newBuilder()
        .size(request.defined.sizeResolver ?: sizeResolver)
        // There's no easy way to be certain whether an image will require sub-sampling in
        // advance so assume it'll be needed and force Coil to write this image to disk.
        .diskCachePolicy(
          when (request.diskCachePolicy) {
            CachePolicy.ENABLED -> CachePolicy.ENABLED
            CachePolicy.READ_ONLY -> CachePolicy.ENABLED
            CachePolicy.WRITE_ONLY,
            CachePolicy.DISABLED -> CachePolicy.WRITE_ONLY
          }
        )
        // This will unfortunately replace any existing target, but it is also the only
        // way to read placeholder images set using ImageRequest#placeholderMemoryCacheKey.
        // Placeholder images should be small in size so sub-sampling isn't needed here.
        .target(
          onStart = {
            resolved = resolved.copy(
              placeholder = it?.asPainter(),
            )
          }
        )
        .build()
    )

    val imageSource = result.toSubSamplingImageSource()
    resolved = resolved.copy(
      crossfadeDuration = result.crossfadeDuration(),
      delegate = if (result is SuccessResult && imageSource != null) {
        ZoomableImageSource.SubSamplingDelegate(
          source = imageSource,
          imageOptions = ImageBitmapOptions(from = (result.drawable as BitmapDrawable).bitmap)
        )
      } else {
        ZoomableImageSource.PainterDelegate(
          painter = result.drawable?.asPainter()
        )
      },
    )
  }

  @OptIn(ExperimentalCoilApi::class)
  private suspend fun ImageResult.toSubSamplingImageSource(): SubSamplingImageSource? {
    val result = this
    val sourceFactory = if (result is SuccessResult && result.drawable is BitmapDrawable) {
      when {
        // Prefer reading of images directly from files whenever possible because
        // it is significantly faster than reading from their input streams.
        result.diskCacheKey != null -> {
          val diskCache = imageLoader.diskCache!!
          val snapshot = diskCache.openSnapshot(result.diskCacheKey!!) ?: error("Coil returned a null cache snapshot")
          ImageSourceFactory { SubSamplingImageSource.file(snapshot.data, preview = it, onClose = snapshot::close) }
        }

        result.dataSource.let { it == DataSource.DISK || it == DataSource.MEMORY_CACHE } -> {
          val requestData = result.request.data
          requestData.asUriOrNull()?.toSourceFactory()
            ?: requestData.asResourceIdOrNull()?.toSourceFactory()
            ?: return null
        }

        else -> return null
      }
    } else {
      return null
    }

    val preview = (result.drawable as? BitmapDrawable)?.bitmap?.asImageBitmap()
    val source = sourceFactory.create(preview)
    return if (source?.canBeSubSampled() == true) source else null
  }

  fun interface ImageSourceFactory {
    suspend fun create(preview: ImageBitmap?): SubSamplingImageSource?
  }

  private fun ImageResult.crossfadeDuration(): Duration {
    val transitionFactory = request.transitionFactory
    return if (this is SuccessResult && transitionFactory is CrossfadeTransition.Factory) {
      // I'm intentionally not using factory.create() because it optimizes crossfade duration
      // to zero if the image was fetched from memory cache. SubSamplingImage will only read
      // bitmaps from the disk so there will always be some delay in showing the image.
      transitionFactory.durationMillis.milliseconds
    } else {
      Duration.ZERO
    }
  }

  private fun Any.asUriOrNull(): Uri? {
    when (this) {
      is String -> return Uri.parse(this)
      is Uri -> return this
      else -> return null
    }
  }

  private fun Uri.toSourceFactory(): ImageSourceFactory {
    // Assets must be evaluated before files because they share the same scheme.
    this.asAssetPathOrNull()?.let { assetPath ->
      return ImageSourceFactory { SubSamplingImageSource.asset(assetPath.path, preview = it) }
    }

    val filePath = when {
      // File URIs without a scheme are invalid but have had historic support
      // from many image loaders, including Coil. Telephoto is forced to support
      // them because it promises to be a drop-in replacement for AsyncImage().
      // https://github.com/saket/telephoto/issues/19
      scheme == null && path?.startsWith('/') == true && pathSegments.isNotEmpty() -> toString().toPath()
      scheme == ContentResolver.SCHEME_FILE -> path?.toPath()
      else -> null
    }
    if (filePath != null) {
      return ImageSourceFactory { SubSamplingImageSource.file(filePath, preview = it) }
    }

    return ImageSourceFactory { SubSamplingImageSource.contentUri(this, preview = it) }
  }

  @JvmInline
  value class ResourceId(val id: Int)

  private fun Any.asResourceIdOrNull(): ResourceId? {
    if (this is Int) {
      runCatching {
        request.context.resources.getResourceEntryName(this)
        return ResourceId(this)
      }
    }
    return null
  }

  private fun ResourceId.toSourceFactory() =
    ImageSourceFactory { SubSamplingImageSource.resource(id, preview = it) }

  private fun Drawable.asPainter(): Painter {
    return DrawablePainter(mutate())
  }
}
