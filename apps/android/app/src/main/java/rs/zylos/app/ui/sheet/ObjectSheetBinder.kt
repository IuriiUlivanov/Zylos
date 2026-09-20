package rs.zylos.app.ui.sheet

import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import rs.zylos.app.R
import rs.zylos.app.data.api.OrgDetailResponse
import rs.zylos.app.databinding.ActivityMapBinding
import rs.zylos.app.databinding.ItemOrgFieldBinding
import rs.zylos.app.map.MapDefaults
import rs.zylos.app.ui.search.SearchDropdownAdapter
import rs.zylos.app.ui.search.SearchRow
import rs.zylos.app.viewmodel.MapUiState
import rs.zylos.app.viewmodel.MapViewModel
import rs.zylos.app.viewmodel.SheetLogic
import rs.zylos.app.viewmodel.SheetMode

class ObjectSheetBinder(
    private val binding: ActivityMapBinding,
    private val viewModel: MapViewModel,
    private val dp: (Int) -> Int,
    private val onControlsChanged: () -> Unit,
    private val onResetCameraPadding: () -> Unit,
) {
    private lateinit var sheetBehavior: BottomSheetBehavior<LinearLayout>
    private var applyingSheetState = false
    private var lastSheetMode: SheetMode = SheetMode.Idle
    var sheetStep: SheetStep = SheetStep.Minimal
        private set

    private val orgAdapter = OrgListAdapter { item -> viewModel.onOrgSelected(item.id) }
    private val searchSheetAdapter = SearchDropdownAdapter(
        onHit = { hit -> viewModel.onSelectHit(hit) },
        onHistory = { },
    )

    val isHidden: Boolean
        get() = !::sheetBehavior.isInitialized || sheetBehavior.state == BottomSheetBehavior.STATE_HIDDEN

    fun bind() {
        val context = binding.root.context
        binding.orgList.layoutManager = LinearLayoutManager(context)
        binding.orgList.adapter = orgAdapter
        binding.searchResultList.layoutManager = LinearLayoutManager(context)
        binding.searchResultList.adapter = searchSheetAdapter
        binding.sheetClose.setOnClickListener { viewModel.onSheetClosed() }

        val handleTap = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    expandSheetByOne()
                    return true
                }
            },
        )
        binding.sheetHandleHit.setOnTouchListener { _, event ->
            handleTap.onTouchEvent(event)
            false
        }

        sheetBehavior = BottomSheetBehavior.from(binding.buildingSheet)
        sheetBehavior.isFitToContents = false
        sheetBehavior.halfExpandedRatio = MapDefaults.SHEET_STEP2_RATIO
        sheetBehavior.peekHeight = dp(MapDefaults.SHEET_STEP1_DP)
        sheetBehavior.isHideable = true
        sheetBehavior.skipCollapsed = false
        sheetBehavior.isDraggable = true
        sheetBehavior.isGestureInsetBottomIgnored = true
        sheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        sheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                stepFromBehavior(newState)?.let { settled ->
                    sheetStep = settled
                    applySheetStepContent()
                }
                if (applyingSheetState) {
                    return
                }
                if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    viewModel.onSheetClosed()
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                applySheetStepContent(slideOffset)
                onControlsChanged()
            }
        })
    }

    fun render(state: MapUiState) {
        val context = binding.root.context
        val modeChanged = lastSheetMode != state.sheet.mode
        val loading = state.sheet.mode == SheetMode.Loading
        when (state.sheet.mode) {
            SheetMode.Idle -> {
                sheetStep = SheetStep.Minimal
                setSheetState(BottomSheetBehavior.STATE_HIDDEN)
                onResetCameraPadding()
                binding.searchResultList.visibility = View.GONE
            }
            SheetMode.Loading -> {
                binding.sheetTitle.setText(R.string.ucitavam)
                binding.sheetSubtitle.text = ""
                binding.buildingContent.visibility = View.VISIBLE
                binding.orgContent.visibility = View.GONE
                binding.searchResultList.visibility = View.GONE
            }
            SheetMode.Building -> {
                binding.sheetTitle.text = SheetLogic.title(state.sheet.building, context.getString(R.string.zgrada))
                binding.sheetSubtitle.text = buildingCountSubtitle(SheetLogic.buildingSubtitleCount(state.sheet.building))
                binding.buildingContent.visibility = View.VISIBLE
                binding.orgContent.visibility = View.GONE
                binding.searchResultList.visibility = View.GONE
                val orgs = state.sheet.building?.organizations.orEmpty()
                orgAdapter.submit(orgs)
                val empty = orgs.isEmpty() && !loading
                binding.orgEmpty.visibility = if (empty) View.VISIBLE else View.GONE
                binding.orgList.visibility = if (empty) View.GONE else View.VISIBLE
            }
            SheetMode.Organization -> {
                val org = state.sheet.org
                binding.sheetTitle.text = org?.name ?: context.getString(R.string.zgrada)
                binding.sheetSubtitle.text = org?.let { SheetLogic.orgSubtitle(it) } ?: ""
                binding.buildingContent.visibility = View.GONE
                binding.orgContent.visibility = View.VISIBLE
                binding.searchResultList.visibility = View.GONE
                if (org != null) {
                    bindOrg(org)
                }
            }
            SheetMode.Peek -> {
                binding.sheetTitle.text = state.sheet.peek?.title ?: ""
                binding.sheetSubtitle.text = state.sheet.peek?.subtitle ?: ""
                binding.buildingContent.visibility = View.GONE
                binding.orgContent.visibility = View.GONE
                binding.searchResultList.visibility = View.GONE
            }
            SheetMode.SearchList -> {
                binding.sheetTitle.setText(R.string.rezultati_pretrage)
                binding.sheetSubtitle.text = context.getString(R.string.rezultati_count, state.search.hits.size)
                binding.buildingContent.visibility = View.GONE
                binding.orgContent.visibility = View.GONE
                binding.searchResultList.visibility = View.VISIBLE
                searchSheetAdapter.submit(state.search.hits.map { SearchRow.Hit(it) })
            }
        }
        if (modeChanged && state.sheet.mode != SheetMode.Idle) {
            sheetStep = SheetAnchors.initialStep()
            setSheetState(behaviorState(sheetStep))
        }
        applySheetStepContent()
        lastSheetMode = state.sheet.mode
        binding.buildingSheet.post { onControlsChanged() }
    }

    private fun buildingCountSubtitle(count: Int): String {
        val context = binding.root.context
        return when {
            count <= 0 -> context.getString(R.string.nema_org)
            count == 1 -> context.getString(R.string.org_count_one, count)
            count in 2..4 -> context.getString(R.string.org_count_few, count)
            else -> context.getString(R.string.org_count_many, count)
        }
    }

    private fun applySheetStepContent(slideOffset: Float? = null) {
        val mode = viewModel.state.value.sheet.mode
        val loading = mode == SheetMode.Loading
        val atMinimal = if (slideOffset != null) {
            slideOffset <= 0.01f
        } else {
            sheetStep == SheetStep.Minimal &&
                sheetBehavior.state != BottomSheetBehavior.STATE_DRAGGING &&
                sheetBehavior.state != BottomSheetBehavior.STATE_SETTLING
        }
        val step = if (atMinimal) SheetStep.Minimal else {
            if (sheetStep == SheetStep.Minimal) SheetStep.Half else sheetStep
        }
        val subtitleOn = SheetAnchors.headerSubtitleVisible(mode, step) &&
            binding.sheetSubtitle.text.isNotBlank()
        binding.sheetSubtitle.visibility = if (subtitleOn) View.VISIBLE else View.GONE
        binding.sheetBody.visibility = if (SheetAnchors.bodyVisible(mode, step)) View.VISIBLE else View.GONE
        binding.sheetLoading.visibility = if (loading && step != SheetStep.Minimal) View.VISIBLE else View.GONE
    }

    private fun expandSheetByOne() {
        if (viewModel.state.value.sheet.mode == SheetMode.Idle) {
            return
        }
        val next = SheetAnchors.next(sheetStep)
        if (next == sheetStep) {
            return
        }
        sheetStep = next
        applySheetStepContent()
        setSheetState(behaviorState(next))
    }

    private fun behaviorState(step: SheetStep): Int {
        return when (step) {
            SheetStep.Minimal -> BottomSheetBehavior.STATE_COLLAPSED
            SheetStep.Half -> BottomSheetBehavior.STATE_HALF_EXPANDED
            SheetStep.Full -> BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun stepFromBehavior(state: Int): SheetStep? {
        return when (state) {
            BottomSheetBehavior.STATE_COLLAPSED -> SheetStep.Minimal
            BottomSheetBehavior.STATE_HALF_EXPANDED -> SheetStep.Half
            BottomSheetBehavior.STATE_EXPANDED -> SheetStep.Full
            else -> null
        }
    }

    private fun bindOrg(org: OrgDetailResponse) {
        bindOrgField(binding.orgAddressRow, R.string.org_field_address, org.address?.label, link = false)
        bindOrgField(binding.orgFloorRow, R.string.org_field_floor, org.floor, link = false)
        val phones = org.phones.orEmpty().filter { it.isNotBlank() }
        bindOrgField(
            binding.orgPhoneRow,
            R.string.org_field_phone,
            phones.joinToString("\n").ifBlank { null },
            link = true,
            phone = true,
        )
        bindOrgField(binding.orgHoursRow, R.string.org_field_hours, org.hours, link = false)
        bindOrgField(binding.orgWebsiteRow, R.string.org_field_website, org.website, link = true, phone = false)
    }

    private fun bindOrgField(
        row: ItemOrgFieldBinding,
        labelRes: Int,
        value: String?,
        link: Boolean,
        phone: Boolean = false,
    ) {
        val context = binding.root.context
        row.orgFieldLabel.setText(labelRes)
        if (value.isNullOrBlank()) {
            row.root.visibility = View.GONE
            return
        }
        row.root.visibility = View.VISIBLE
        row.orgFieldValue.text = value
        row.orgFieldValue.linksClickable = link
        if (link) {
            row.orgFieldValue.setTextColor(ContextCompat.getColor(context, R.color.accent))
            row.orgFieldValue.autoLinkMask = if (phone) Linkify.PHONE_NUMBERS else Linkify.WEB_URLS
            Linkify.addLinks(row.orgFieldValue, row.orgFieldValue.autoLinkMask)
            row.orgFieldValue.movementMethod = LinkMovementMethod.getInstance()
        } else {
            row.orgFieldValue.setTextColor(ContextCompat.getColor(context, R.color.ink))
            row.orgFieldValue.autoLinkMask = 0
            row.orgFieldValue.movementMethod = null
        }
    }

    private fun setSheetState(target: Int) {
        if (target == BottomSheetBehavior.STATE_HIDDEN) {
            sheetBehavior.isHideable = true
        } else {
            binding.buildingSheet.visibility = View.VISIBLE
            if (sheetBehavior.state == BottomSheetBehavior.STATE_HIDDEN) {
                sheetBehavior.isHideable = true
            }
        }
        if (sheetBehavior.state == target) {
            if (target != BottomSheetBehavior.STATE_HIDDEN) {
                sheetBehavior.isHideable = false
            }
            return
        }
        applyingSheetState = true
        sheetBehavior.state = target
        binding.buildingSheet.postDelayed({
            applyingSheetState = false
            if (target != BottomSheetBehavior.STATE_HIDDEN) {
                sheetBehavior.isHideable = false
            }
        }, MapDefaults.SHEET_ANIMATION_MS.toLong())
    }
}
