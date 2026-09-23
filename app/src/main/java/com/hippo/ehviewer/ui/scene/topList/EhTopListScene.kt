package com.hippo.ehviewer.ui.scene.topList

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.FrameLayout
import android.widget.Spinner
import androidx.annotation.IntDef
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhClient
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhRequest
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.EhTopListDetail
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.client.data.topList.TopListInfo
import com.hippo.ehviewer.client.data.topList.TopListItem
import com.hippo.ehviewer.client.exception.EhException
import com.hippo.ehviewer.ui.scene.BaseScene
import com.hippo.ehviewer.ui.scene.EhCallback
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListScene
import com.hippo.ehviewer.util.ClipboardUtil.createAnnouncerFromClipboardUrl
import com.hippo.scene.Announcer
import com.hippo.scene.SceneFragment
import com.hippo.view.ViewTransition
import com.hippo.widget.recyclerview.AutoStaggeredGridLayoutManager
import java.util.Random

private const val STATE_INIT = -1
private const val STATE_NORMAL = 0
private const val STATE_REFRESH = 1
private const val STATE_REFRESH_HEADER = 2
private const val STATE_FAILED = 3
private const val BACK_PRESSED_INTERVAL = 2000
private const val TRANSITION_ANIMATION_DISABLED = true
private const val GALLERY_TOP_LIST_INDEX = 0

private var mPosition = 0

class EhTopListScene : BaseScene() {

    @IntDef(STATE_INIT, STATE_NORMAL, STATE_REFRESH, STATE_REFRESH_HEADER, STATE_FAILED)
    @Retention(AnnotationRetention.SOURCE)
    private annotation class State

    private var pressBackTime = 0L

    @State
    private var state = STATE_INIT

    private var ehTopListDetail: EhTopListDetail? = null
    private var viewTransition: ViewTransition? = null
    private var recyclerView: RecyclerView? = null
    private var client: EhClient? = null
    private var request: EhRequest? = null
    private var hasFirstRefresh = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 画廊排行榜每个时间段（昨日/本月/今年/全部）对应的画廊，元素可能为 null。 */
    private var galleryPeriods: List<List<GalleryInfo?>>? = null
    private var galleryThumbsRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ehContext = ehContext ?: return
        client = EhApplication.getEhClient(ehContext)
    }

    override fun onCreateView2(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = inflater.inflate(R.layout.scene_gallery_top_list, container, false)

        val spinner = view.findViewById<Spinner>(R.id.top_list_spinner)
        spinner.setSelection(0)
        spinner.onItemSelectedListener = TopListKindSelectedListener()

        val frameLayout = view.findViewById<FrameLayout>(R.id.page_detail_view)
        val transitionView = view.findViewById<View>(R.id.data_loading_view)
        viewTransition = ViewTransition(transitionView, frameLayout)

        recyclerView = view.findViewById(R.id.top_list_recycler_view)
        val context = ehContext
        val columnSize = context?.resources
            ?.getDimensionPixelOffset(Settings.getThumbSizeResId()) ?: 0
        val layoutManager = AutoStaggeredGridLayoutManager(
            columnSize, StaggeredGridLayoutManager.VERTICAL)
        layoutManager.setStrategy(AutoStaggeredGridLayoutManager.STRATEGY_SUITABLE_SIZE)
        recyclerView?.layoutManager = layoutManager

        if (!hasFirstRefresh) {
            hasFirstRefresh = true
            try {
                loadData()
            } catch (e: EhException) {
                e.printStackTrace()
            }
        } else {
            bindViewSecond(mPosition)
            adjustViewVisibility(STATE_NORMAL, true)
        }

        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mainHandler.removeCallbacksAndMessages(null)
        viewTransition = null
    }

    override fun onBackPressed() {
        val handle = checkDoubleClickExit()
        if (!handle) {
            if (state == STATE_INIT) {
                request?.cancel()
            }
            finish()
        }
    }

    private fun checkDoubleClickExit(): Boolean {
        if (stackIndex != 0) {
            return false
        }

        val time = System.currentTimeMillis()
        return if (time - pressBackTime > BACK_PRESSED_INTERVAL) {
            pressBackTime = time
            showTip(R.string.press_twice_exit, LENGTH_SHORT)
            true
        } else {
            false
        }
    }

    @Throws(EhException::class)
    private fun loadData() {
        val requested = request()
        if (!requested) {
            throw EhException("请求数据失败请更换IP地址或检查网络设置是否正确~")
        }
    }

    private fun request(): Boolean {
        val context = ehContext ?: return false
        val activity = activity2 ?: return false
        val ehClient = client ?: return false
        val url = EhUrl.getTopListUrl()

        val callback = GetTopListDetailListener(context, activity.stageId, tag)

        request = EhRequest()
            .setMethod(EhClient.METHOD_GET_TOP_LIST)
            .setArgs(url)
            .setCallback(callback)

        ehClient.execute(request)
        return true
    }

    private fun onGetEhTopListDetailSuccess(detail: EhTopListDetail, index: Int) {
        ehTopListDetail = detail
        if (galleryPeriods == null) {
            galleryPeriods = buildGalleryPeriods(detail.get(GALLERY_TOP_LIST_INDEX))
        }
        bindViewSecond(index)
        adjustViewVisibility(STATE_NORMAL, true)
        requestGalleryThumbs()
    }

    private fun buildGalleryPeriods(info: TopListInfo?): List<List<GalleryInfo?>> {
        if (info == null) {
            return emptyList()
        }
        val periods = ArrayList<List<GalleryInfo?>>(info.size())
        for (period in 0 until info.size()) {
            val array = info.get(period)
            if (array == null) {
                periods.add(emptyList())
                continue
            }
            val galleries = ArrayList<GalleryInfo?>(array.length())
            for (i in 0 until array.length()) {
                val item = array.get(i)
                val gid = item?.gid?.toLongOrNull()
                if (item == null || gid == null || item.token.isNullOrEmpty()) {
                    galleries.add(null)
                    continue
                }
                galleries.add(GalleryInfo().apply {
                    this.gid = gid
                    token = item.token
                    title = item.value
                })
            }
            periods.add(galleries)
        }
        return periods
    }

    /**
     * 排行榜页面本身不含封面，需要用 api.php 批量补全缩略图信息（每 25 个画廊一次请求）。
     */
    private fun requestGalleryThumbs() {
        if (galleryThumbsRequested) {
            return
        }
        val context = ehContext ?: return
        val periods = galleryPeriods ?: return
        val galleries = periods.flatten().filterNotNull()
        if (galleries.isEmpty()) {
            return
        }
        galleryThumbsRequested = true
        val okHttpClient = EhApplication.getOkHttpClient(context)
        val executor = EhApplication.getExecutorService(context)
        val referer = EhUrl.getTopListUrl()
        executor.execute {
            try {
                EhEngine.fillGalleryListByApi(null, okHttpClient, galleries, referer)
            } catch (e: Throwable) {
                // 封面获取失败时保留占位卡片
            }
            mainHandler.post {
                val rv = recyclerView ?: return@post
                val adapter = rv.adapter
                if (adapter is EhTopListAdapter) {
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun bindViewSecond(index: Int) {
        val detail = ehTopListDetail ?: return
        val rv = recyclerView ?: return
        val context = ehContext ?: return
        val periods = if (index == GALLERY_TOP_LIST_INDEX) galleryPeriods else null
        rv.adapter = EhTopListAdapterView(context, detail.get(index), this, index, periods)
    }

    private fun adjustViewVisibility(@State newState: Int, animation: Boolean) {
        val transition = viewTransition ?: return
        state = newState
        val shouldAnimate = !TRANSITION_ANIMATION_DISABLED && animation

        when (newState) {
            STATE_INIT, STATE_REFRESH -> transition.showView(0, shouldAnimate)
            else -> transition.showView(1, shouldAnimate)
        }
    }

    private inner class GetTopListDetailListener(
        context: Context,
        stageId: Int,
        sceneTag: String?,
    ) : EhCallback<EhTopListScene, EhTopListDetail>(context, stageId, sceneTag) {
        override fun isInstance(scene: SceneFragment): Boolean = scene is EhTopListScene

        override fun onSuccess(result: EhTopListDetail) {
            onGetEhTopListDetailSuccess(result, 0)
        }

        override fun onFailure(e: Exception) {
        }

        override fun onCancel() {
        }
    }

    private inner class EhTopListAdapterView(
        context: Context,
        topListInfo: TopListInfo,
        private val sceneFragment: SceneFragment,
        searchType: Int,
        galleryPeriods: List<List<GalleryInfo?>>?,
    ) : EhTopListAdapter(context, topListInfo, searchType, galleryPeriods) {

        private val hashMap = HashMap<Int, Int>()

        override fun clickTitle(urlFollow: String) {
            val urlBuilder = ListUrlBuilder()
            urlBuilder.mode = ListUrlBuilder.MODE_TOP_LIST
            urlBuilder.setFollow(urlFollow)
            GalleryListScene.startScene(sceneFragment, urlBuilder)
        }

        override fun getRandomColor(position: Int): Int {
            hashMap[position]?.let { return it }
            val random = Random()
            val color = Color.argb(160, random.nextInt(256), random.nextInt(256), random.nextInt(256))
            hashMap[position] = color
            return color
        }

        override fun onItemClick(topListItem: TopListItem, searchType: Int) {
            val urlBuilder = ListUrlBuilder()
            if (searchType == 0) {
                urlBuilder.mode = ListUrlBuilder.MODE_NORMAL
            } else {
                urlBuilder.mode = ListUrlBuilder.MODE_UPLOADER
            }

            if (!topListItem.gid.isNullOrEmpty() && !topListItem.token.isNullOrEmpty()) {
                // The toplist link already carries the gallery token, so the detail can be opened
                // without resolving it first.
                val args = Bundle()
                args.putString(GalleryDetailScene.KEY_ACTION, GalleryDetailScene.ACTION_GID_TOKEN)
                args.putLong(GalleryDetailScene.KEY_GID, topListItem.gid.toLong())
                args.putString(GalleryDetailScene.KEY_TOKEN, topListItem.token)
                val announcer = Announcer(GalleryDetailScene::class.java).setArgs(args)
                startScene(announcer)
                return
            } else if (topListItem.href != null) {
                val announcer = createAnnouncerFromClipboardUrl(topListItem.href)
                if (announcer != null) {
                    startScene(announcer)
                    return
                }
            }

            urlBuilder.keyword = topListItem.value
            GalleryListScene.startScene(sceneFragment, urlBuilder)
        }
    }

    private inner class TopListKindSelectedListener : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            mPosition = position
            bindViewSecond(mPosition)
        }

        override fun onNothingSelected(parent: AdapterView<*>?) {
        }
    }

    companion object {
        const val KEY_ACTION = "action"
        const val ACTION_TOP_LIST = "action_top_list"
    }
}
