// Every event the payload has, and no `_` arm. Outside compose-rust that does not build,
// because an event added later would otherwise be a breaking change for this match.
use compose_rust::EventPayload;

fn handle(payload: &EventPayload<'_>) {
    match payload {
        EventPayload::Clicked => {}
        EventPayload::TextChanged(_) => {}
        EventPayload::TextSubmitted(_) => {}
        EventPayload::FocusLost => {}
        EventPayload::ProtocolError { .. } => {}
        EventPayload::KeyDown { .. } => {}
        EventPayload::RangeRequested { .. } => {}
        EventPayload::ValueChanged(_) => {}
        EventPayload::WindowSizeChanged { .. } => {}
        EventPayload::FilesEntered => {}
        EventPayload::FilesDropped(_) => {}
        EventPayload::DesignSystemResolved(_) => {}
        EventPayload::Resync => {}
        EventPayload::LifecycleStart => {}
        EventPayload::LifecycleStop => {}
        EventPayload::NotificationActivated { .. } => {}
        EventPayload::NotificationPermissionChanged(_) => {}
        EventPayload::CodeChanged { .. } => {}
        EventPayload::CodeEditRejected { .. } => {}
        EventPayload::CodeHovered { .. } => {}
        EventPayload::CodeSaveRequested { .. } => {}
        EventPayload::DecorationActivated { .. } => {}
        EventPayload::AnimationEvent { .. } => {}
        EventPayload::ReducedMotionChanged(_) => {}
        EventPayload::ColorSchemeChanged(_) => {}
        EventPayload::Pointer { .. } => {}
        EventPayload::ZoomChanged { .. } => {}
        EventPayload::DensityChanged { .. } => {}
        EventPayload::SelectionChanged { .. } => {}
        EventPayload::VisibleRangeChanged { .. } => {}
        EventPayload::EditorFocusChanged { .. } => {}
        EventPayload::FoldChanged { .. } => {}
        EventPayload::CompositionChanged { .. } => {}
        EventPayload::WindowCloseRequested => {}
        EventPayload::WindowFocusChanged { .. } => {}
        EventPayload::WindowMoved { .. } => {}
        EventPayload::WindowDrag { .. } => {}
        EventPayload::WindowDragOver { .. } => {}
        EventPayload::WindowUnavailable => {}
        EventPayload::WindowClosed => {}
        EventPayload::WindowRequested { .. } => {}
        EventPayload::ApplicationReopen => {}
        EventPayload::FocusGained => {}
        EventPayload::KeyUp { .. } => {}
        EventPayload::CodeMetrics { .. } => {}
        EventPayload::CodeScrolled { .. } => {}
        EventPayload::FileDialogResult { .. } => {}
        EventPayload::ClipboardText { .. } => {}
        EventPayload::IoAnswer { .. } => {}
        EventPayload::FileHandles { .. } => {}
        EventPayload::StorageLocation(_) => {}
        EventPayload::ScrollChanged { .. } => {}
        EventPayload::MotionFinished { .. } => {}
        EventPayload::CaretRect { .. } => {}
        EventPayload::PositionRect { .. } => {}
        EventPayload::CodePointer { .. } => {}
        EventPayload::SelectionsChanged { .. } => {}
        EventPayload::CodeEditGroupResult { .. } => {}
        EventPayload::NativeViewParent { .. } => {}
        EventPayload::NativeViewFocus { .. } => {}
        EventPayload::TextFormatAtSelection(_) => {}
        EventPayload::RichTextChanged { .. } => {}
        EventPayload::AssetWanted { .. } => {}
    }
}

fn main() {
    handle(&EventPayload::Clicked);
}
