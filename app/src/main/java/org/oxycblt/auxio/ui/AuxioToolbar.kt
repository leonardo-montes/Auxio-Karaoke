/*
 * Copyright (c) 2026 Auxio Project
 * ButtonGroupToolbar.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.oxycblt.auxio.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.annotation.MenuRes
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.view.SupportMenuInflater
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.view.menu.MenuItemImpl
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.MenuCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.TextViewCompat
import com.google.android.material.R as MR
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ViewToolbarBinding

/**
 * A compound toolbar view that owns its entire layout instead of piggybacking on AppCompat's
 * internal Toolbar structure. It preserves the small API surface Auxio actually uses while
 * rendering action items as an M3 Expressive [com.google.android.material.button.MaterialButtonGroup].
 */
class AuxioToolbar
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = androidx.appcompat.R.attr.toolbarStyle,
) : FrameLayout(context, attrs, defStyleAttr) {
    private var menuItemClickListener: Toolbar.OnMenuItemClickListener? = null
    private var navigationClickListener: OnClickListener? = null
    private var navigationIconDrawable: Drawable? = null
    private var navigationContentDescriptionText: CharSequence? = null
    private var titleText: CharSequence? = null
    private var subtitleText: CharSequence? = null
    private var titleCentered = false
    private var subtitleCentered = false
    private var overflowClickListener: ((View) -> Unit)? = null
    private var isInflatingInternalLayout = true
    private var _binding: ViewToolbarBinding? = null
    private val binding: ViewToolbarBinding
        get() = checkNotNull(_binding) { "Toolbar binding was not initialized" }
    private val actionButtons = mutableMapOf<Int, RippleFixMaterialButton>()
    @SuppressLint("RestrictedApi") private var menuBuilder = MenuBuilder(context)

    init {
        _binding = ViewToolbarBinding.inflate(LayoutInflater.from(context), this, true)
        isInflatingInternalLayout = false

        clipChildren = false
        clipToPadding = false

        binding.toolbarRoot.clipChildren = false
        binding.toolbarRoot.clipToPadding = false
        binding.toolbarContentFrame.clipChildren = false
        binding.toolbarContentFrame.clipToPadding = false
        binding.toolbarActionGroup.clipChildren = false
        binding.toolbarActionGroup.clipToPadding = false
        binding.toolbarActionGroup.spacing = 0

        configureIconButton(binding.toolbarNavigationButton)

        val toolbarAttrs =
            context.obtainStyledAttributes(
                attrs,
                androidx.appcompat.R.styleable.Toolbar,
                defStyleAttr,
                0,
            )
        val materialToolbarAttrs =
            context.obtainStyledAttributes(attrs, MR.styleable.MaterialToolbar, defStyleAttr, 0)

        titleText = toolbarAttrs.getText(androidx.appcompat.R.styleable.Toolbar_title)
        subtitleText = toolbarAttrs.getText(androidx.appcompat.R.styleable.Toolbar_subtitle)
        binding.toolbarTitle.text = titleText
        binding.toolbarSubtitle.text = subtitleText
        binding.toolbarSubtitle.isVisible = !subtitleText.isNullOrEmpty()

        val titleTextAppearance =
            toolbarAttrs.getResourceId(androidx.appcompat.R.styleable.Toolbar_titleTextAppearance, 0)
        if (titleTextAppearance != 0) {
            setTitleTextAppearance(titleTextAppearance)
        }

        val subtitleTextAppearance =
            toolbarAttrs.getResourceId(
                androidx.appcompat.R.styleable.Toolbar_subtitleTextAppearance,
                0,
            )
        if (subtitleTextAppearance != 0) {
            setSubtitleTextAppearance(subtitleTextAppearance)
        }

        val titleTextColor =
            toolbarAttrs.getColorStateList(androidx.appcompat.R.styleable.Toolbar_titleTextColor)
        if (titleTextColor != null) {
            setTitleTextColor(titleTextColor)
        }

        val subtitleTextColor =
            toolbarAttrs.getColorStateList(
                androidx.appcompat.R.styleable.Toolbar_subtitleTextColor
            )
        if (subtitleTextColor != null) {
            setSubtitleTextColor(subtitleTextColor)
        }

        titleCentered =
            materialToolbarAttrs.getBoolean(MR.styleable.MaterialToolbar_titleCentered, false)
        subtitleCentered =
            materialToolbarAttrs.getBoolean(MR.styleable.MaterialToolbar_subtitleCentered, false)
        updateTextGravity()

        setNavigationIcon(toolbarAttrs.getDrawable(androidx.appcompat.R.styleable.Toolbar_navigationIcon))
        setNavigationContentDescription(
            toolbarAttrs.getText(androidx.appcompat.R.styleable.Toolbar_navigationContentDescription)
        )

        val menuResId = toolbarAttrs.getResourceId(androidx.appcompat.R.styleable.Toolbar_menu, 0)

        toolbarAttrs.recycle()
        materialToolbarAttrs.recycle()

        updateNavigationButton()
        updateCenterContentVisibility()

        if (menuResId != 0) {
            inflateMenu(menuResId)
        }
    }

    override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams?) {
        val binding = _binding
        if (isInflatingInternalLayout || binding == null || child === binding.root) {
            super.addView(child, index, params)
            return
        }

        binding.toolbarContentFrame.addView(child, params)
        updateCenterContentVisibility()
    }

    override fun removeView(view: View) {
        val binding = _binding
        if (
            binding != null &&
                view.parent === binding.toolbarContentFrame &&
                view !== binding.toolbarTitleContainer
        ) {
            binding.toolbarContentFrame.removeView(view)
            updateCenterContentVisibility()
            return
        }

        super.removeView(view)
    }

    var title: CharSequence?
        get() = titleText
        set(value) {
            titleText = value
            binding.toolbarTitle.text = value
            updateCenterContentVisibility()
        }

    fun setTitle(@StringRes resId: Int) {
        title = if (resId != 0) context.getText(resId) else null
    }

    var subtitle: CharSequence?
        get() = subtitleText
        set(value) {
            subtitleText = value
            binding.toolbarSubtitle.text = value
            binding.toolbarSubtitle.isVisible = !value.isNullOrEmpty()
            updateCenterContentVisibility()
        }

    fun setSubtitle(@StringRes resId: Int) {
        subtitle = if (resId != 0) context.getText(resId) else null
    }

    fun setTitleTextAppearance(@StyleRes resId: Int) {
        TextViewCompat.setTextAppearance(binding.toolbarTitle, resId)
    }

    fun setSubtitleTextAppearance(@StyleRes resId: Int) {
        TextViewCompat.setTextAppearance(binding.toolbarSubtitle, resId)
    }

    fun setTitleTextColor(color: ColorStateList) {
        binding.toolbarTitle.setTextColor(color)
    }

    fun setSubtitleTextColor(color: ColorStateList) {
        binding.toolbarSubtitle.setTextColor(color)
    }

    fun setNavigationIcon(@DrawableRes resId: Int) {
        setNavigationIcon(
            if (resId != 0) AppCompatResources.getDrawable(context, resId) else null
        )
    }

    fun setNavigationIcon(icon: Drawable?) {
        navigationIconDrawable = icon
        updateNavigationButton()
    }

    fun getNavigationIcon(): Drawable? = navigationIconDrawable

    fun setNavigationContentDescription(description: CharSequence?) {
        navigationContentDescriptionText = description
        updateNavigationButton()
    }

    fun setNavigationContentDescription(@StringRes resId: Int) {
        setNavigationContentDescription(if (resId != 0) context.getText(resId) else null)
    }

    fun getNavigationContentDescription(): CharSequence? = navigationContentDescriptionText

    fun setNavigationOnClickListener(listener: OnClickListener?) {
        navigationClickListener = listener
        updateNavigationButton()
    }

    val menu: Menu
        get() = menuBuilder

    fun inflateMenu(@MenuRes resId: Int) {
        val builder = MenuBuilder(context)
        SupportMenuInflater(context).inflate(resId, builder)
        menuBuilder = builder
        rebuildActionButtons()
    }

    fun setOnMenuItemClickListener(listener: Toolbar.OnMenuItemClickListener?) {
        menuItemClickListener = listener
    }

    fun getMenuButton(itemId: Int): RippleFixMaterialButton? = actionButtons[itemId]

    fun setMenuItemEnabled(itemId: Int, enabled: Boolean) {
        menuBuilder.findItem(itemId)?.isEnabled = enabled
        getMenuButton(itemId)?.isEnabled = enabled
    }

    fun getTitleContainer(): View = binding.toolbarTitleContainer

    /**
     * Override the overflow button's click behavior. When set, the overflow button will call
     * [block] instead of showing the default popup menu.
     */
    fun overrideOnOverflowMenuClick(block: (View) -> Unit) {
        overflowClickListener = block
    }

    private fun configureIconButton(button: RippleFixMaterialButton) {
        button.minimumWidth = resources.getDimensionPixelSize(R.dimen.size_touchable_small)
        button.minimumHeight = resources.getDimensionPixelSize(R.dimen.size_touchable_small)
        button.iconSize = resources.getDimensionPixelSize(R.dimen.size_icon_small)
    }

    private fun updateNavigationButton() {
        binding.toolbarNavigationButton.apply {
            icon = navigationIconDrawable
            contentDescription = navigationContentDescriptionText
            isVisible = navigationIconDrawable != null
            setOnClickListener(navigationClickListener)
            TooltipCompat.setTooltipText(this, navigationContentDescriptionText)
        }
    }

    private fun updateTextGravity() {
        binding.toolbarTitle.apply {
            gravity = if (titleCentered) android.view.Gravity.CENTER_HORIZONTAL else android.view.Gravity.START
            textAlignment =
                if (titleCentered) {
                    View.TEXT_ALIGNMENT_CENTER
                } else {
                    View.TEXT_ALIGNMENT_VIEW_START
                }
        }
        binding.toolbarSubtitle.apply {
            gravity =
                if (subtitleCentered) android.view.Gravity.CENTER_HORIZONTAL else android.view.Gravity.START
            textAlignment =
                if (subtitleCentered) {
                    View.TEXT_ALIGNMENT_CENTER
                } else {
                    View.TEXT_ALIGNMENT_VIEW_START
                }
        }
    }

    private fun updateCenterContentVisibility() {
        val hasCustomContent =
            binding.toolbarContentFrame.children.any { it !== binding.toolbarTitleContainer }
        val showBuiltInTitle = !hasCustomContent && (!titleText.isNullOrEmpty() || !subtitleText.isNullOrEmpty())
        binding.toolbarTitleContainer.isVisible = showBuiltInTitle
    }

    @SuppressLint("RestrictedApi")
    private fun rebuildActionButtons() {
        actionButtons.clear()
        binding.toolbarActionGroup.removeAllViews()

        val actionItems = mutableListOf<MenuItemImpl>()
        val overflowItems = mutableListOf<MenuItemImpl>()
        for (i in 0 until menuBuilder.size()) {
            val item = menuBuilder.getItem(i) as MenuItemImpl
            if (!item.isVisible) continue
            if (item.requiresActionButton() || item.requestsActionButton()) {
                actionItems.add(item)
            } else {
                overflowItems.add(item)
            }
        }

        for (item in actionItems) {
            createActionButton(item)?.let { button ->
                binding.toolbarActionGroup.addView(button)
                actionButtons[item.itemId] = button
            }
        }

        if (overflowItems.isNotEmpty()) {
            binding.toolbarActionGroup.addView(createOverflowButton(overflowItems))
        }

        binding.toolbarActionGroup.isVisible = binding.toolbarActionGroup.childCount > 0
    }

    @SuppressLint("RestrictedApi")
    private fun createActionButton(item: MenuItemImpl): RippleFixMaterialButton? {
        val button =
            RippleFixMaterialButton(
                createActionButtonContext(item.itemId),
                null,
                MR.attr.materialIconButtonStyle,
            ).apply {
                configureIconButton(this)
                id = item.itemId
                icon = item.icon
                contentDescription = item.title
                isEnabled = item.isEnabled
                TooltipCompat.setTooltipText(this, item.title)
                setOnClickListener { view ->
                    if (item.hasSubMenu()) {
                        showPopupMenu(view, item.subMenu)
                    } else {
                        menuItemClickListener?.onMenuItemClick(item)
                    }
                }
            }

        return button
    }

    @SuppressLint("RestrictedApi")
    private fun createOverflowButton(overflowItems: List<MenuItemImpl>): RippleFixMaterialButton {
        return RippleFixMaterialButton(context, null, MR.attr.materialIconButtonStyle).apply {
            configureIconButton(this)
            setIconResource(R.drawable.ic_more_vert_24)
            contentDescription = context.getString(R.string.lbl_more)
            TooltipCompat.setTooltipText(this, contentDescription)
            setOnClickListener { view ->
                val customListener = overflowClickListener
                if (customListener != null) {
                    customListener(view)
                } else {
                    showPopupMenu(view, overflowItems, menuBuilder)
                }
            }
        }
    }

    private fun showPopupMenu(anchor: View, menu: Menu?) {
        if (menu == null) {
            return
        }
        showPopupMenu(anchor, List(menu.size()) { menu.getItem(it) }.filter { it.isVisible }, menu)
    }

    private fun showPopupMenu(anchor: View, items: List<MenuItem>, sourceMenu: Menu) {
        val popup = PopupMenu(context, anchor)
        MenuCompat.setGroupDividerEnabled(
            popup.menu,
            (sourceMenu as? MenuBuilder)?.isGroupDividerEnabled ?: false,
        )

        val originalItems = mutableMapOf<Int, MenuItem>()
        for (item in items) {
            popup.menu.add(item.groupId, item.itemId, item.order, item.title).apply {
                icon = item.icon
                isEnabled = item.isEnabled
                isCheckable = item.isCheckable
                isChecked = item.isChecked
            }
            originalItems[item.itemId] = item
        }

        popup.setOnMenuItemClickListener { clickedItem ->
            val originalItem = originalItems[clickedItem.itemId] ?: return@setOnMenuItemClickListener false
            if (originalItem.hasSubMenu()) {
                showPopupMenu(anchor, originalItem.subMenu)
                true
            } else {
                menuItemClickListener?.onMenuItemClick(originalItem) ?: false
            }
        }
        popup.show()
    }

    private fun createActionButtonContext(itemId: Int): Context {
        val themeOverlayRes =
            when (itemId) {
                R.id.action_play -> R.style.ThemeOverlay_Auxio_IconButton_Style_Small_Secondary
                R.id.action_shuffle -> R.style.ThemeOverlay_Auxio_IconButton_Style_Small_Primary
                else -> 0
            }

        return if (themeOverlayRes != 0) ContextThemeWrapper(context, themeOverlayRes) else context
    }
}
