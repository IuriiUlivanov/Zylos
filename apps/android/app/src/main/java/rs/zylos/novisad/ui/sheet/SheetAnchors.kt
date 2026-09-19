package rs.zylos.novisad.ui.sheet

import rs.zylos.novisad.viewmodel.SheetMode

/** Three discrete object-sheet sizes — Docs/design/object-card/README.md */
enum class SheetStep {
    Minimal,
    Half,
    Full,
}

object SheetAnchors {
    fun next(step: SheetStep): SheetStep {
        return when (step) {
            SheetStep.Minimal -> SheetStep.Half
            SheetStep.Half -> SheetStep.Full
            SheetStep.Full -> SheetStep.Full
        }
    }

    fun previous(step: SheetStep): SheetStep {
        return when (step) {
            SheetStep.Full -> SheetStep.Half
            SheetStep.Half -> SheetStep.Minimal
            SheetStep.Minimal -> SheetStep.Minimal
        }
    }

    fun initialStep(): SheetStep = SheetStep.Half

    fun headerSubtitleVisible(mode: SheetMode, step: SheetStep): Boolean {
        if (step == SheetStep.Minimal || mode == SheetMode.Idle) {
            return false
        }
        return mode == SheetMode.Building ||
            mode == SheetMode.Loading ||
            mode == SheetMode.Organization ||
            mode == SheetMode.Peek ||
            mode == SheetMode.SearchList
    }

    fun bodyVisible(mode: SheetMode, step: SheetStep): Boolean {
        if (step == SheetStep.Minimal) {
            return false
        }
        return mode == SheetMode.Building || mode == SheetMode.Organization || mode == SheetMode.SearchList
    }
}
