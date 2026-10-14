package cn.edu.bucea.library.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.bucea.library.AppThemeColor

@Composable
internal fun ThemeColorPicker(selectedColor: AppThemeColor, onSelect: (AppThemeColor) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("当前：${selectedColor.label} · 默认建大紫", color = Muted, fontSize = 12.sp)
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AppThemeColor.entries.forEach { themeColor ->
                val isSelected = themeColor == selectedColor
                val swatch = Color(themeColor.primaryArgb)
                Surface(
                    selected = isSelected,
                    onClick = { onSelect(themeColor) },
                    modifier = Modifier.semantics { role = Role.RadioButton },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) SoftAccent else Paper,
                    border = BorderStroke(1.dp, if (isSelected) Accent else Line),
                ) {
                    Row(
                        modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Box(Modifier.size(26.dp).clip(CircleShape).background(swatch), contentAlignment = Alignment.Center) {
                            if (isSelected) Icon(Icons.Outlined.Check, null, Modifier.size(17.dp), tint = Color.White)
                        }
                        Text(themeColor.label, color = Ink, fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium)
                    }
                }
            }
        }
        Text("点击即可切换，选择会自动保存。", color = Muted, fontSize = 12.sp)
    }
}
