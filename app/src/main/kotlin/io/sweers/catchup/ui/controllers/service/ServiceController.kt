/*
 * Copyright (c) 2017 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.sweers.catchup.ui.controllers.service

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Parcelable
import android.support.annotation.ColorInt
import android.support.graphics.drawable.AnimatedVectorDrawableCompat
import android.support.v4.content.ContextCompat
import android.support.v4.widget.SwipeRefreshLayout
import android.support.v7.util.DiffUtil
import android.support.v7.util.DiffUtil.DiffResult
import android.support.v7.widget.GridLayoutManager
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.RecyclerView.Adapter
import android.support.v7.widget.RecyclerView.RecycledViewPool
import android.support.v7.widget.RecyclerView.ViewHolder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import butterknife.BindView
import butterknife.OnClick
import com.apollographql.apollo.exception.ApolloException
import com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader
import com.bumptech.glide.util.ViewPreloadSizeProvider
import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.uber.autodispose.kotlin.autoDisposeWith
import dagger.Subcomponent
import dagger.android.AndroidInjector
import io.reactivex.android.schedulers.AndroidSchedulers
import io.sweers.catchup.R
import io.sweers.catchup.R.drawable
import io.sweers.catchup.R.layout
import io.sweers.catchup.R.string
import io.sweers.catchup.data.RemoteConfigKeys
import io.sweers.catchup.injection.ConductorInjection
import io.sweers.catchup.injection.scopes.PerController
import io.sweers.catchup.requestManager
import io.sweers.catchup.service.api.CatchUpItem
import io.sweers.catchup.service.api.DataRequest
import io.sweers.catchup.service.api.DisplayableItem
import io.sweers.catchup.service.api.Service
import io.sweers.catchup.ui.InfiniteScrollListener
import io.sweers.catchup.ui.Scrollable
import io.sweers.catchup.ui.base.ButterKnifeController
import io.sweers.catchup.ui.base.CatchUpItemViewHolder
import io.sweers.catchup.ui.base.DataLoadingSubject
import io.sweers.catchup.ui.base.DataLoadingSubject.DataLoadingCallbacks
import io.sweers.catchup.ui.controllers.SmmryController
import io.sweers.catchup.ui.controllers.service.LoadResult.DiffResultData
import io.sweers.catchup.ui.controllers.service.LoadResult.NewData
import io.sweers.catchup.util.Iterables
import io.sweers.catchup.util.applyOn
import io.sweers.catchup.util.d
import io.sweers.catchup.util.e
import io.sweers.catchup.util.hide
import io.sweers.catchup.util.isVisible
import io.sweers.catchup.util.show
import jp.wasabeef.recyclerview.animators.FadeInUpAnimator
import retrofit2.HttpException
import java.io.IOException
import java.security.InvalidParameterException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Provider

abstract class DisplayableItemAdapter<T : DisplayableItem, VH : ViewHolder>(
    val columnCount: Int = 1)
  : Adapter<VH>(), DataLoadingCallbacks {

  companion object Blah {
    const val TYPE_ITEM = 0
    const val TYPE_LOADING_MORE = -1
  }

  protected val data = mutableListOf<T>()

  internal fun update(loadResult: LoadResult<T>) {
    when (loadResult) {
      is NewData -> {
        data.addAll(loadResult.newData)
        notifyItemRangeInserted(data.size - loadResult.newData.size, loadResult.newData.size)
      }
      is DiffResultData -> {
        data.clear()
        data.addAll(loadResult.data)
        loadResult.diffResult.dispatchUpdatesTo(this)
      }
    }
  }

  fun getItems() = data.toList()

  fun getItemColumnSpan(position: Int) = when (getItemViewType(position)) {
    TYPE_LOADING_MORE -> columnCount
    else -> 1
  }
}

class ServiceController : ButterKnifeController,
    SwipeRefreshLayout.OnRefreshListener, Scrollable, DataLoadingSubject {

  companion object {
    val ARG_SERVICE_KEY = "serviceKey"
    fun newInstance(serviceKey: String) =
        ServiceController(Bundle().apply {
          putString(
              ARG_SERVICE_KEY,
              serviceKey)
        })
  }

  @BindView(R.id.error_container) lateinit var errorView: View
  @BindView(R.id.error_message) lateinit var errorTextView: TextView
  @BindView(R.id.error_image) lateinit var errorImage: ImageView
  @BindView(R.id.list) lateinit var recyclerView: RecyclerView
  @BindView(R.id.progress) lateinit var progress: ProgressBar
  @BindView(R.id.refresh) lateinit var swipeRefreshLayout: SwipeRefreshLayout

  private lateinit var layoutManager: LinearLayoutManager
  private lateinit var adapter: DisplayableItemAdapter<out DisplayableItem, ViewHolder>
  private var currentPage: String? = null
  private var nextPage: String? = null
  private var isRestoring = false
  private var pageToRestoreTo: String? = null
  private var moreDataAvailable = true
  private var dataLoading = false
  private var pendingRVState: Parcelable? = null

  @Inject lateinit var remoteConfig: FirebaseRemoteConfig
  @Inject lateinit var viewPool: RecycledViewPool
  @Inject lateinit var services: Map<String, @JvmSuppressWildcards Provider<StorageBackedService>>
  private val service: Service by lazy {
    args[ARG_SERVICE_KEY].let {
      services[it]?.get() ?: throw IllegalArgumentException("No service provided for $it!")
    }
  }

  @Suppress("unused")
  constructor() : super()

  @Suppress("unused")
  constructor(args: Bundle) : super(args)

  override fun toString() = "ServiceController: ${args[ARG_SERVICE_KEY]}"

  override fun isDataLoading(): Boolean = dataLoading

  override fun inflateView(inflater: LayoutInflater, container: ViewGroup): View =
      inflater.inflate(R.layout.controller_basic_news, container, false)

  override fun bind(view: View) = ServiceController_ViewBinding(this,
      view)

  override fun onContextAvailable(context: Context) {
    ConductorInjection.inject(this)
    nextPage = service.meta().firstPageKey
    super.onContextAvailable(context)
  }

  private fun createLayoutManager(context: Context,
      adapter: DisplayableItemAdapter<*, *>): LinearLayoutManager {
    return if (service.meta().isVisual) {
      GridLayoutManager(context, 2).apply {
        spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
          override fun getSpanSize(position: Int) = adapter.getItemColumnSpan(position)
        }
      }
    } else {
      LinearLayoutManager(activity, LinearLayoutManager.VERTICAL, false)
    }
  }

  private fun createAdapter(
      context: Context): DisplayableItemAdapter<out DisplayableItem, ViewHolder> {
    if (service.meta().isVisual) {
      val adapter = ImageAdapter(context) { item, holder ->
        service.bindItemView(item.delegate, holder)
      }
      val preloader = RecyclerViewPreloader<ImageItem>(requestManager(),
          adapter,
          ViewPreloadSizeProvider<ImageItem>(),
          ImageAdapter.PRELOAD_AHEAD_ITEMS)
      recyclerView.addOnScrollListener(preloader)
      return adapter
    } else {
      return TextAdapter { item, holder ->
        service.bindItemView(item, holder)
        item.summarizationInfo?.let {
          if (remoteConfig.getBoolean(RemoteConfigKeys.SMMRY_ENABLED)) {
            holder.itemLongClicks()
                .autoDisposeWith(this)
                .subscribe(SmmryController.showFor<Any>(controller = this,
                    service = service,
                    title = item.title,
                    id = item.id.toString(),
                    info = it))
          }
        }
      }
    }
  }

  override fun onViewBound(view: View) {
    super.onViewBound(view)
    @ColorInt val accentColor = ContextCompat.getColor(view.context, service.meta().themeColor)
    swipeRefreshLayout.run {
      setColorSchemeColors(accentColor)
      setOnRefreshListener(this@ServiceController)
    }
    progress.indeterminateTintList = ColorStateList.valueOf(accentColor)
    adapter = createAdapter(view.context)
    layoutManager = createLayoutManager(view.context, adapter)
    recyclerView.layoutManager = layoutManager
    recyclerView.addOnScrollListener(
        object : InfiniteScrollListener(layoutManager, this@ServiceController) {
          override fun onLoadMore() {
            loadData()
          }
        })
    recyclerView.recycledViewPool = viewPool
    recyclerView.adapter = adapter
    recyclerView.itemAnimator = FadeInUpAnimator(OvershootInterpolator(1f)).apply {
      addDuration = 300
      removeDuration = 300
    }
  }

  @OnClick(R.id.retry_button) internal fun onRetry() {
    errorView.hide()
    progress.show()
    onRefresh()
  }

  @OnClick(R.id.error_image) internal fun onErrorClick(imageView: ImageView) {
    (imageView.drawable as AnimatedVectorDrawableCompat).start()
  }

  override fun onAttach(view: View) {
    super.onAttach(view)
    if (adapter.itemCount == 0) {
      // What's the right way to do this in Conductor? This will always be called after onResume
      swipeRefreshLayout.isEnabled = false
      loadData()
    }
  }

  override fun onDetach(view: View) {
    nextPage = null
    moreDataAvailable = true
    super.onDetach(view)
  }

  override fun onSaveViewState(view: View, outState: Bundle) {
    outState.run {
      if (currentPage != service.meta().firstPageKey) {
        putString("currentPage", currentPage)
      }
      putParcelable("layoutManagerState", recyclerView.layoutManager.onSaveInstanceState())
    }
    super.onSaveViewState(view, outState)
  }

  override fun onRestoreViewState(view: View, savedViewState: Bundle) {
    super.onRestoreViewState(view, savedViewState)
    with(savedViewState) {
      pageToRestoreTo = getString("currentPage", null)
      pendingRVState = getParcelable("layoutManagerState")
      isRestoring = pendingRVState != null
      if (isRestoring) {
        errorView.hide()
        recyclerView.show()
      }
    }
  }

  private fun loadData(fromRefresh: Boolean = false) {
    if (!recyclerView.isVisible()) {
      progress.show()
    }
    if (fromRefresh || adapter.itemCount == 0) {
      moreDataAvailable = true
      nextPage = service.meta().firstPageKey
    }
    if (nextPage == null) {
      moreDataAvailable = false
    }
    if (!moreDataAvailable) {
      return
    }
    val pageToRequest = pageToRestoreTo ?: nextPage!!
    dataLoading = true
    if (adapter.itemCount != 0) {
      recyclerView.post { adapter.dataStartedLoading() }
      recyclerView.itemAnimator = null
    }
    val trace = FirebasePerformance.getInstance().newTrace("Data load - ${service.meta().id}")
    val timer = AtomicLong()
    val multiPage = pageToRestoreTo != null
    if (multiPage) {
      recyclerView.itemAnimator = null
    }
    service.fetchPage(
        DataRequest(
            fromRefresh = fromRefresh && !isRestoring,
            multiPage = multiPage,
            pageId = pageToRequest)
            .also {
              isRestoring = false
              pageToRestoreTo = null
            })
        .map { result ->
          result.data.also {
            nextPage = result.nextPageToken
            currentPage = pageToRequest
          }
        }
        .flattenAsObservable { it }
        .let {
          // If these are images, wrap in our visual item
          if (service.meta().isVisual) {
            it.map { ImageItem(it) }
          } else it
        }
        .cast(DisplayableItem::class.java)
        .toList()
        .toMaybe()
        .map { newData ->
          if (fromRefresh) {
            DiffResultData(newData,
                DiffUtil.calculateDiff(
                    ItemUpdateCallback(adapter.getItems(),
                        newData)))
          } else {
            NewData(newData)
          }
        }
        .observeOn(AndroidSchedulers.mainThread())
        .doOnEvent { _, _ ->
          swipeRefreshLayout.isEnabled = true
          swipeRefreshLayout.isRefreshing = false
        }
        .doOnSubscribe {
          trace.start()
          timer.set(System.currentTimeMillis())
        }
        .doFinally {
          trace.stop()
          d { "Data load - ${service.meta().id} - took: ${System.currentTimeMillis() - timer.get()}ms" }
          dataLoading = false
          recyclerView.post {
            adapter.dataFinishedLoading()
          }
        }
        .doOnComplete { moreDataAvailable = false }
        .autoDisposeWith(this)
        .subscribe({ loadResult ->
          applyOn(progress, errorView) { hide() }
          swipeRefreshLayout.show()
          recyclerView.post {
            when (adapter) {
              is TextAdapter -> {
                @Suppress("UNCHECKED_CAST") // badpokerface.png
                (adapter as TextAdapter).update((loadResult as LoadResult<CatchUpItem>))
              }
              is ImageAdapter -> {
                @Suppress("UNCHECKED_CAST")
                (adapter as ImageAdapter).update((loadResult as LoadResult<ImageItem>))
              }
            }
            pendingRVState?.let {
              recyclerView.layoutManager.onRestoreInstanceState(it)
              pendingRVState = null
            }
          }
        }, { error: Throwable ->
          val activity = activity
          if (activity != null) {
            when (error) {
              is IOException -> {
                progress.hide()
                errorTextView.text = activity.getString(string.connection_issue)
                swipeRefreshLayout.hide()
                errorView.show()
                AnimatedVectorDrawableCompat.create(activity, drawable.avd_no_connection)
                    ?.let {
                      errorImage.setImageDrawable(it)
                      it.start()
                    }
                e(error) { "IOException" }
              }
              is HttpException, is ApolloException -> {
                // TODO Show some sort of API error response.
                progress.hide()
                errorTextView.text = activity.getString(string.api_issue)
                swipeRefreshLayout.hide()
                errorView.show()
                AnimatedVectorDrawableCompat.create(activity, drawable.avd_no_connection)
                    ?.let {
                      errorImage.setImageDrawable(it)
                      it.start()
                    }
                e(error) { "HttpException" }
              }
              else -> {
                // TODO Show some sort of generic response error
                progress.hide()
                swipeRefreshLayout.hide()
                errorTextView.text = activity.getString(string.unknown_issue)
                errorView.show()
                AnimatedVectorDrawableCompat.create(activity, drawable.avd_no_connection)
                    ?.let {
                      errorImage.setImageDrawable(it)
                      it.start()
                    }
                e(error) { "Unknown issue." }
              }
            }
          } else {
            pageToRestoreTo = pageToRequest
          }
        })
  }

  override fun onRefresh() {
    loadData(true)
  }

  override fun onRequestScrollToTop() {
    if (layoutManager.findFirstVisibleItemPosition() > 50) {
      recyclerView.scrollToPosition(0)
    } else {
      recyclerView.smoothScrollToPosition(0)
    }
  }

  private class TextAdapter(
      private val bindDelegate: (CatchUpItem, CatchUpItemViewHolder) -> Unit)
    : DisplayableItemAdapter<CatchUpItem, ViewHolder>() {

    private var showLoadingMore = false

    init {
      setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
      if (getItemViewType(position) == TYPE_LOADING_MORE) {
        return RecyclerView.NO_ID
      }
      return Iterables.get(data, position)
          .stableId()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
      val layoutInflater = LayoutInflater.from(parent.context)
      when (viewType) {
        TYPE_ITEM -> return CatchUpItemViewHolder(
            layoutInflater.inflate(R.layout.list_item_general,
                parent,
                false))
        TYPE_LOADING_MORE -> return LoadingMoreHolder(
            layoutInflater.inflate(layout.infinite_loading,
                parent,
                false))
      }
      throw InvalidParameterException("Unrecognized view type - " + viewType)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
      when (getItemViewType(position)) {
        TYPE_ITEM -> try {
          bindDelegate(Iterables.get(data, position), holder as CatchUpItemViewHolder)
        } catch (error: Exception) {
          e(error) { "Bind delegate failure!" }
        }

        TYPE_LOADING_MORE -> (holder as LoadingMoreHolder).progress.visibility = if (position > 0) View.VISIBLE else View.INVISIBLE
      }
    }

    override fun getItemCount() = dataItemCount + if (showLoadingMore) 1 else 0

    private val dataItemCount: Int
      get() = data.size

    private val loadingMoreItemPosition: Int
      get() = if (showLoadingMore) itemCount - 1 else RecyclerView.NO_POSITION

    override fun getItemViewType(position: Int): Int {
      if (position < dataItemCount && dataItemCount > 0) {
        return TYPE_ITEM
      }
      return TYPE_LOADING_MORE
    }

    override fun dataStartedLoading() {
      if (showLoadingMore) {
        return
      }
      showLoadingMore = true
      notifyItemInserted(loadingMoreItemPosition)
    }

    override fun dataFinishedLoading() {
      if (!showLoadingMore) {
        return
      }
      val loadingPos = loadingMoreItemPosition
      showLoadingMore = false
      notifyItemRemoved(loadingPos)
    }
  }

  @Subcomponent
  @PerController
  interface Component : AndroidInjector<ServiceController> {

    @Subcomponent.Builder
    abstract class Builder : AndroidInjector.Builder<ServiceController>()
  }
}

class LoadingMoreHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
  val progress: ProgressBar = itemView as ProgressBar
}

@Suppress("unused")
internal sealed class LoadResult<T : DisplayableItem> {
  data class DiffResultData<T : DisplayableItem>(val data: List<T>,
      val diffResult: DiffResult) : LoadResult<T>()

  data class NewData<T : DisplayableItem>(val newData: List<T>) : LoadResult<T>()
}

internal class ItemUpdateCallback<T : DisplayableItem>(
    private val oldItems: List<T>,
    private val newItems: List<T>
) : DiffUtil.Callback() {
  override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
      oldItems[oldItemPosition].stableId() == newItems[newItemPosition].stableId()

  override fun getOldListSize() = oldItems.size

  override fun getNewListSize() = newItems.size

  override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
      oldItems[oldItemPosition] == newItems[newItemPosition]
}