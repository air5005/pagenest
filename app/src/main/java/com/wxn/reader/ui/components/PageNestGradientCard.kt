package com.wxn.reader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.wxn.reader.ui.theme.PageNestBrandGradient
import com.wxn.reader.ui.theme.PageNestShapes
import com.wxn.reader.ui.theme.PageNestSpacing

@Composable
fun PageNestGradientCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(PageNestShapes.LargeCard)
            .background(PageNestBrandGradient)
            .padding(PageNestSpacing.ScreenHorizontal),
        content = content,
    )
}
