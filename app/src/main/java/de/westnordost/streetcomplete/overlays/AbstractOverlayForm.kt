package de.westnordost.streetcomplete.overlays

import android.content.res.Configuration
import android.location.Location
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.annotation.UiThread
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.viewbinding.ViewBinding
import de.westnordost.countryboundaries.CountryBoundaries
import de.westnordost.osmfeatures.FeatureDictionary
import de.westnordost.streetcomplete.R
import de.westnordost.streetcomplete.data.meta.CountryInfo
import de.westnordost.streetcomplete.data.meta.CountryInfos
import de.westnordost.streetcomplete.data.meta.getByLocation
import de.westnordost.streetcomplete.data.osm.edits.AddElementEditsController
import de.westnordost.streetcomplete.data.osm.edits.ElementEditAction
import de.westnordost.streetcomplete.data.osm.edits.ElementEditType
import de.westnordost.streetcomplete.data.osm.edits.ElementEditsController
import de.westnordost.streetcomplete.data.osm.geometry.ElementGeometry
import de.westnordost.streetcomplete.data.osm.geometry.ElementPolylinesGeometry
import de.westnordost.streetcomplete.data.osm.mapdata.Element
import de.westnordost.streetcomplete.data.osm.mapdata.ElementKey
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.data.osm.mapdata.Way
import de.westnordost.streetcomplete.data.overlays.OverlayRegistry
import de.westnordost.streetcomplete.databinding.FragmentOverlayBinding
import de.westnordost.streetcomplete.quests.AnswerItem
import de.westnordost.streetcomplete.screens.main.bottom_sheet.IsCloseableBottomSheet
import de.westnordost.streetcomplete.screens.main.bottom_sheet.IsMapOrientationAware
import de.westnordost.streetcomplete.screens.main.checkIsSurvey
import de.westnordost.streetcomplete.util.FragmentViewBindingPropertyDelegate
import de.westnordost.streetcomplete.util.getNameAndLocationLabelString
import de.westnordost.streetcomplete.util.ktx.isSplittable
import de.westnordost.streetcomplete.util.ktx.popIn
import de.westnordost.streetcomplete.util.ktx.popOut
import de.westnordost.streetcomplete.util.ktx.setMargins
import de.westnordost.streetcomplete.util.ktx.toast
import de.westnordost.streetcomplete.util.ktx.viewLifecycleScope
import de.westnordost.streetcomplete.view.RoundRectOutlineProvider
import de.westnordost.streetcomplete.view.insets_animation.respectSystemInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.android.ext.android.inject
import org.koin.core.qualifier.named
import java.util.concurrent.FutureTask

/** Abstract base class for any form displayed for an overlay */
abstract class AbstractOverlayForm :
    Fragment(), IsShowingElement, IsCloseableBottomSheet, IsMapOrientationAware {

    // dependencies
    private val elementEditsController: ElementEditsController by inject()
    private val countryInfos: CountryInfos by inject()
    private val countryBoundaries: FutureTask<CountryBoundaries> by inject(named("CountryBoundariesFuture"))
    private val overlayRegistry: OverlayRegistry by inject()
    private val featureDictionaryFuture: FutureTask<FeatureDictionary> by inject(named("FeatureDictionaryFuture"))
    protected val featureDictionary: FeatureDictionary get() = featureDictionaryFuture.get()
    private var _countryInfo: CountryInfo? = null // lazy but resettable because based on lateinit var
        get() {
            if (field == null) {
                field = countryInfos.getByLocation(
                    countryBoundaries.get(),
                    geometry.center.longitude,
                    geometry.center.latitude,
                )
            }
            return field
        }
    protected val countryInfo get() = _countryInfo!!

    // only used for testing / only used for ShowQuestFormsActivity! Found no better way to do this
    var addElementEditsController: AddElementEditsController = elementEditsController

    // view / state
    private var _binding: FragmentOverlayBinding? = null
    private val binding get() = _binding!!

    private var startedOnce = false

    // passed in parameters
    protected lateinit var overlay: Overlay private set
    protected lateinit var element: Element private set
    protected lateinit var geometry: ElementGeometry private set
    private var initialMapRotation = 0f
    private var initialMapTilt = 0f
    override val elementKey: ElementKey get() = ElementKey(element.type, element.id)

    // overridable by child classes
    open val contentLayoutResId: Int? = null
    open val contentPadding = true
    open val otherAnswers = listOf<AnswerItem>()

    interface Listener {
        /** The GPS position at which the user is displayed at */
        val displayedMapLocation: Location?

        /** Called when the user successfully answered the quest */
        fun onEdited(editType: ElementEditType, element: Element, geometry: ElementGeometry)

        /** Called when the user chose to leave a note instead */
        fun onComposeNote(editType: ElementEditType, element: Element, geometry: ElementGeometry, leaveNoteContext: String)

        /** Called when the user chose to split the way */
        fun onSplitWay(editType: ElementEditType, way: Way, geometry: ElementPolylinesGeometry)
    }
    private val listener: Listener? get() = parentFragment as? Listener ?: activity as? Listener

    /* --------------------------------------- Lifecycle --------------------------------------- */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val args = requireArguments()
        overlay = overlayRegistry.getByName(args.getString(ARG_OVERLAY)!!)!!
        element = Json.decodeFromString(args.getString(ARG_ELEMENT)!!)
        geometry = Json.decodeFromString(args.getString(ARG_GEOMETRY)!!)
        initialMapRotation = args.getFloat(ARG_MAP_ROTATION)
        initialMapTilt = args.getFloat(ARG_MAP_TILT)
        _countryInfo = null // reset lazy field

        /* deliberately did not copy the mobile-country-code hack from AbstractQuestForm because
           this is kind of deprecated and should not be used for new code */
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        _binding = FragmentOverlayBinding.inflate(inflater, container, false)
        contentLayoutResId?.let { setContentView(it) }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.bottomSheetContainer.respectSystemInsets(View::setMargins)

        val cornerRadius = resources.getDimension(R.dimen.speech_bubble_rounded_corner_radius)
        val margin = resources.getDimensionPixelSize(R.dimen.horizontal_speech_bubble_margin)
        binding.speechbubbleContentContainer.outlineProvider = RoundRectOutlineProvider(
            cornerRadius, margin, margin, margin, margin
        )

        binding.titleHintLabel.text = getNameAndLocationLabelString(element.tags, resources, featureDictionary)

        binding.moreButton.setOnClickListener {
            showOtherAnswers()
        }
        binding.okButton.setOnClickListener {
            if (!isFormComplete()) {
                activity?.toast(R.string.no_changes)
            } else {
                onClickOk()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // see rant comment in AbstractBottomSheetFragment
        resources.updateConfiguration(newConfig, resources.displayMetrics)

        binding.bottomSheetContainer.updateLayoutParams { width = resources.getDimensionPixelSize(R.dimen.quest_form_width) }
    }

    override fun onStart() {
        super.onStart()

        checkIsFormComplete()

        if (!startedOnce) {
            onMapOrientation(initialMapRotation, initialMapTilt)
            startedOnce = true
        }
    }

    override fun onMapOrientation(rotation: Float, tilt: Float) {
        // default empty implementation
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /* --------------------------------- IsCloseableBottomSheet  ------------------------------- */

    @UiThread override fun onClickMapAt(position: LatLon, clickAreaSizeInMeters: Double): Boolean {
        return false
    }

    /** Request to close the form through user interaction (back button, clicked other quest,..),
     * requires user confirmation if any changes have been made  */
    @UiThread override fun onClickClose(onConfirmed: () -> Unit) {
        if (!isRejectingClose()) {
            onDiscard()
            onConfirmed()
        } else {
            activity?.let {
                AlertDialog.Builder(it)
                    .setMessage(R.string.confirmation_discard_title)
                    .setPositiveButton(R.string.confirmation_discard_positive) { _, _ ->
                        onDiscard()
                        onConfirmed()
                    }
                    .setNegativeButton(R.string.short_no_answer_on_button, null)
                    .show()
            }
        }
    }

    /* ------------------------------- Interface for subclasses  ------------------------------- */

    /** Inflate given layout resource id into the content view and return the inflated view */
    protected fun setContentView(resourceId: Int): View {
        if (binding.content.childCount > 0) {
            binding.content.removeAllViews()
        }
        binding.content.visibility = View.VISIBLE
        updateContentPadding()
        layoutInflater.inflate(resourceId, binding.content)
        return binding.content.getChildAt(0)
    }

    private fun updateContentPadding() {
        if (!contentPadding) {
            binding.content.setPadding(0, 0, 0, 0)
        } else {
            val horizontal = resources.getDimensionPixelSize(R.dimen.quest_form_horizontal_padding)
            val vertical = resources.getDimensionPixelSize(R.dimen.quest_form_vertical_padding)
            binding.content.setPadding(horizontal, vertical, horizontal, vertical)
        }
    }

    protected fun applyEdit(answer: ElementEditAction) {
        viewLifecycleScope.launch {
            solve(answer)
        }
    }

    protected fun checkIsFormComplete() {
        binding.okButton.isEnabled = hasChanges()
        if (isFormComplete()) {
            binding.okButtonContainer.popIn()
        } else {
            binding.okButtonContainer.popOut()
        }
    }

    private fun isRejectingClose(): Boolean = hasChanges()

    protected abstract fun hasChanges(): Boolean

    protected open fun onDiscard() {}

    protected open fun isFormComplete(): Boolean = false

    protected abstract fun onClickOk()

    protected inline fun <reified T : ViewBinding> contentViewBinding(
        noinline viewBinder: (View) -> T
    ) = FragmentViewBindingPropertyDelegate(this, viewBinder, R.id.content)

    /* -------------------------------------- ...-Button -----------------------------------------*/

    private fun showOtherAnswers() {
        val answers = assembleOtherAnswers()
        val popup = PopupMenu(requireContext(), binding.moreButton)
        for (i in answers.indices) {
            val otherAnswer = answers[i]
            val order = answers.size - i
            popup.menu.add(Menu.NONE, i, order, otherAnswer.titleResourceId)
        }
        popup.show()

        popup.setOnMenuItemClickListener { item ->
            answers[item.itemId].action()
            true
        }
    }

    private fun assembleOtherAnswers(): List<AnswerItem> {
        val answers = mutableListOf<AnswerItem>()

        answers.add(AnswerItem(R.string.leave_note) { composeNote() })

        if (element.isSplittable()) {
            answers.add(AnswerItem(R.string.split_way) { splitWay() })
        }

        answers.addAll(otherAnswers)
        return answers
    }

    protected fun splitWay() {
        listener?.onSplitWay(overlay, element as Way, geometry as ElementPolylinesGeometry)
    }

    protected fun composeNote() {
        val overlayTitle = requireContext().getString(overlay.title)
        val leaveNoteContext = "In context of \"$overlayTitle\" overlay"
        listener?.onComposeNote(overlay, element, geometry, leaveNoteContext)
    }

    /* -------------------------------------- Apply edit  -------------------------------------- */

    private suspend fun solve(action: ElementEditAction) {
        setLocked(true)
        if (!checkIsSurvey(requireContext(), geometry, listOfNotNull(listener?.displayedMapLocation))) {
            setLocked(false)
            return
        }
        withContext(Dispatchers.IO) {
            addElementEditsController.add(overlay, element, geometry, "survey", action)
        }
        listener?.onEdited(overlay, element, geometry)
    }

    private fun setLocked(locked: Boolean) {
        binding.glassPane.isGone = !locked
    }

    companion object {
        private const val ARG_ELEMENT = "element"
        private const val ARG_GEOMETRY = "geometry"
        private const val ARG_OVERLAY = "overlay"
        private const val ARG_MAP_ROTATION = "map_rotation"
        private const val ARG_MAP_TILT = "map_tilt"

        fun createArguments(overlay: Overlay, element: Element, geometry: ElementGeometry, rotation: Float, tilt: Float) = bundleOf(
            ARG_ELEMENT to Json.encodeToString(element),
            ARG_GEOMETRY to Json.encodeToString(geometry),
            ARG_OVERLAY to overlay.name,
            ARG_MAP_ROTATION to rotation,
            ARG_MAP_TILT to tilt
        )
    }
}

data class AnswerItem(val titleResourceId: Int, val action: () -> Unit)
