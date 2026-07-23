(ns glimmer.ffi
  "Raw C bindings for GTK4 + GLib/GObject/GIO. A thin defcfn layer — no logic.
  The reactive toolkit is built on top of these in glimmer.widget / glimmer.core.

  Pointers are plain machine addresses (jolt numbers). GTK uses floating
  references for newly created widgets; containers sink the ref when a child is
  appended. The toolkit takes ownership of top-level windows by sinking them via
  g-object-ref-sink, and lets containers manage their children.

  Signal handlers are connected with g-signal-connect-data — the canonical C
  symbol behind the g_signal_connect macro. Handlers are jolt fns wrapped by
  glimmer.core via jolt.ffi/foreign-callable (:collect-safe), because GTK
  invokes them from inside the blocking g_application_run main loop."
  (:require [jolt.ffi :as ffi]))

;; --- constants ---------------------------------------------------------------
;; GApplicationFlags — 0 is G_APPLICATION_DEFAULT_FLAGS
(def APPLICATION-DEFAULT-FLAGS 0)

;; GConnectFlags — 0 is no flags (behaves like g_signal_connect)
(def CONNECT-DEFAULT 0)

;; --- GObject enum introspection (table-free constant resolution) -------------
;; Used by glimmer.genum to resolve a GEnum member nick (:start, :fill) to its
;; integer value at runtime, with no enum-constant table in the library.
;; g_type_from_name returns the GType (a gsize) for a registered type, or 0 if
;; the type isn't registered yet (GType registration is lazy). g_type_class_ref
;; returns the class struct — for an enum type, a GEnumClass*.
;; g_enum_get_value_by_nick looks a member up by its lowercase nick, returning a
;; GEnumValue* = { gint value; const gchar *value_name; const gchar *value_nick; }
;; whose first field is the integer we want.
(ffi/defcfn g-type-from-name        "g_type_from_name"        [:string] :size_t)
(ffi/defcfn g-type-class-ref        "g_type_class_ref"        [:size_t] :pointer)
(ffi/defcfn g-enum-get-value-by-nick "g_enum_get_value_by_nick" [:pointer :string] :pointer)

;; --- application / main loop (libgio, libglib, libgtk-4) ---------------------
;; gtk_application_new returns a GtkApplication (a GApplication subclass) — needed
;; so gtk_application_window_new can attach managed windows to the app.
(ffi/defcfn gtk-application-new "gtk_application_new" [:string :uint] :pointer)
(ffi/defcfn g-application-new  "g_application_new"  [:string :uint] :pointer)
;; g_application_run blocks running the GTK main loop; mark it :blocking so the
;; GC isn't pinned while it runs and signal callbacks stay collect-safe.
(ffi/defcfn g-application-run  "g_application_run"  [:pointer :int :pointer] :int :blocking)
(ffi/defcfn g-application-quit "g_application_quit" [:pointer] :void)
;; g_timeout_add(interval_ms, GSourceFunc, data) — schedules a callback on the
;; main loop. Used by examples/tests to auto-quit the blocking app loop.
(ffi/defcfn g-timeout-add "g_timeout_add" [:uint :pointer :pointer] :uint)
;; g_idle_add(GSourceFunc, data) — schedules a one-shot callback on the main
;; loop, i.e. the main thread while a GTK app is running. Used to marshal
;; reactive re-renders triggered off the main thread (an nREPL eval mutating a
;; ratom on its worker thread) back onto it. The callback returns 0 (FALSE) so
;; the source runs once and is removed.
(ffi/defcfn g-idle-add "g_idle_add" [:pointer :pointer] :uint)

;; --- windows (libgtk-4) ------------------------------------------------------
(ffi/defcfn gtk-application-window-new "gtk_application_window_new" [:pointer] :pointer)
(ffi/defcfn gtk-window-new             "gtk_window_new"             [] :pointer)
(ffi/defcfn gtk-window-set-title       "gtk_window_set_title"       [:pointer :string] :void)
(ffi/defcfn gtk-window-set-default-size "gtk_window_set_default_size" [:pointer :int :int] :void)
(ffi/defcfn gtk-window-set-child       "gtk_window_set_child"       [:pointer :pointer] :void)
(ffi/defcfn gtk-window-present         "gtk_window_present"         [:pointer] :void)

;; --- boxes / layout containers ----------------------------------------------
(ffi/defcfn gtk-box-new              "gtk_box_new"              [:int :int] :pointer)
(ffi/defcfn gtk-box-append           "gtk_box_append"           [:pointer :pointer] :void)
(ffi/defcfn gtk-box-remove           "gtk_box_remove"           [:pointer :pointer] :void)
(ffi/defcfn gtk-box-reorder-child-after "gtk_box_reorder_child_after" [:pointer :pointer :pointer] :void)
(ffi/defcfn gtk-box-set-spacing      "gtk_box_set_spacing"      [:pointer :int] :void)
;; orientation is the GtkOrientable property, not a GtkBox setter
(ffi/defcfn gtk-orientable-set-orientation "gtk_orientable_set_orientation" [:pointer :int] :void)
(ffi/defcfn gtk-box-set-homogeneous  "gtk_box_set_homogeneous"  [:pointer :int] :void)

;; --- widget tree traversal ---------------------------------------------------
;; Walk a container's children in visual order: get_first_child, then
;; get_next_sibling until it returns NULL (0). Lets tests/examples read GTK's
;; actual child order back (e.g. to verify keyed reordering).
(ffi/defcfn gtk-widget-get-first-child  "gtk_widget_get_first_child"  [:pointer] :pointer)
(ffi/defcfn gtk-widget-get-next-sibling "gtk_widget_get_next_sibling" [:pointer] :pointer)

;; --- widgets -----------------------------------------------------------------
(ffi/defcfn gtk-button-new              "gtk_button_new"              [] :pointer)
(ffi/defcfn gtk-button-new-with-label   "gtk_button_new_with_label"   [:string] :pointer)
(ffi/defcfn gtk-button-set-label        "gtk_button_set_label"        [:pointer :string] :void)

(ffi/defcfn gtk-label-new               "gtk_label_new"               [:string] :pointer)
(ffi/defcfn gtk-label-set-text          "gtk_label_set_text"          [:pointer :string] :void)
(ffi/defcfn gtk-label-set-label         "gtk_label_set_label"         [:pointer :string] :void)
(ffi/defcfn gtk-label-set-xalign        "gtk_label_set_xalign"        [:pointer :float] :void)
(ffi/defcfn gtk-label-set-markup        "gtk_label_set_markup"        [:pointer :string] :void)
;; Wrapping/ellipsizing bound a label's natural width so a long line can't drive
;; its container (and a resizable window) ever wider. :wrap + :max-width-chars is
;; the standard fix; :ellipsize is the alternative that truncates with an ellipsis.
(ffi/defcfn gtk-label-set-wrap          "gtk_label_set_wrap"           [:pointer :int] :void)
(ffi/defcfn gtk-label-set-width-chars   "gtk_label_set_width_chars"    [:pointer :int] :void)
(ffi/defcfn gtk-label-set-max-width-chars "gtk_label_set_max_width_chars" [:pointer :int] :void)
(ffi/defcfn gtk-label-set-lines         "gtk_label_set_lines"          [:pointer :int] :void)
(ffi/defcfn gtk-label-set-ellipsize     "gtk_label_set_ellipsize"      [:pointer :int] :void)

(ffi/defcfn gtk-entry-new               "gtk_entry_new"               [] :pointer)
;; GtkEditable interface (implemented by GtkEntry):
(ffi/defcfn gtk-editable-get-text       "gtk_editable_get_text"       [:pointer] :string)
(ffi/defcfn gtk-editable-set-text       "gtk_editable_set_text"       [:pointer :string] :void)
(ffi/defcfn gtk-editable-set-placeholder-text "gtk_entry_set_placeholder_text" [:pointer :string] :void)

(ffi/defcfn gtk-checkbutton-new               "gtk_check_button_new"               [] :pointer)
(ffi/defcfn gtk-checkbutton-new-with-label    "gtk_check_button_new_with_label"    [:string] :pointer)
(ffi/defcfn gtk-checkbutton-set-active        "gtk_check_button_set_active"        [:pointer :int] :void)
(ffi/defcfn gtk-checkbutton-get-active        "gtk_check_button_get_active"        [:pointer] :int)

(ffi/defcfn gtk-separator-new           "gtk_separator_new"           [:int] :pointer)

;; --- generic widget state & layout -------------------------------------------
;; The margin/halign/hexpand setters are GtkWidget props — they apply to every
;; widget, not just a specific kind, so glimmer.widget applies them to all tags.
;; halign/valign take a GtkAlign enum value, resolved at runtime by glimmer.genum
;; from an idiomatic keyword nick (:start, :fill, :center).
(ffi/defcfn gtk-widget-set-visible    "gtk_widget_set_visible"    [:pointer :int] :void)
(ffi/defcfn gtk-widget-set-sensitive  "gtk_widget_set_sensitive"  [:pointer :int] :void)
(ffi/defcfn gtk-widget-set-tooltip-text "gtk_widget_set_tooltip_text" [:pointer :string] :void)
(ffi/defcfn gtk-widget-set-margin-start   "gtk_widget_set_margin_start"   [:pointer :int] :void)
(ffi/defcfn gtk-widget-set-margin-end     "gtk_widget_set_margin_end"     [:pointer :int] :void)
(ffi/defcfn gtk-widget-set-margin-top     "gtk_widget_set_margin_top"     [:pointer :int] :void)
(ffi/defcfn gtk-widget-set-margin-bottom  "gtk_widget_set_margin_bottom"  [:pointer :int] :void)
(ffi/defcfn gtk-widget-set-halign "gtk_widget_set_halign" [:pointer :int] :void)
(ffi/defcfn gtk-widget-set-valign "gtk_widget_set_valign" [:pointer :int] :void)
(ffi/defcfn gtk-widget-set-hexpand "gtk_widget_set_hexpand" [:pointer :int] :void)
(ffi/defcfn gtk-widget-set-vexpand "gtk_widget_set_vexpand" [:pointer :int] :void)
(ffi/defcfn gtk-widget-set-size-request "gtk_widget_set_size_request" [:pointer :int :int] :void)

;; --- frame (single-child container with an optional label) -------------------
(ffi/defcfn gtk-frame-new       "gtk_frame_new"       [:string] :pointer)
(ffi/defcfn gtk-frame-set-label "gtk_frame_set_label" [:pointer :string] :void)
(ffi/defcfn gtk-frame-set-child "gtk_frame_set_child" [:pointer :pointer] :void)

;; --- scrolled window (single-child viewport that scrolls instead of growing) --
;; GTK4 propagates the child's natural size by default, which would make a
;; scrolled window grow to fit its child rather than scroll. The toolkit turns
;; propagation off at construction so the child scrolls within the allotted area.
(ffi/defcfn gtk-scrolled-window-new "gtk_scrolled_window_new" [:pointer :pointer] :pointer)
(ffi/defcfn gtk-scrolled-window-set-child "gtk_scrolled_window_set_child" [:pointer :pointer] :void)
(ffi/defcfn gtk-scrolled-window-set-propagate-natural-height
  "gtk_scrolled_window_set_propagate_natural_height" [:pointer :int] :void)
(ffi/defcfn gtk-scrolled-window-set-propagate-natural-width
  "gtk_scrolled_window_set_propagate_natural_width" [:pointer :int] :void)

;; --- signals & reference counting (libgobject) -------------------------------
;; g_signal_connect_data(instance, detailed_signal, c_handler, data, destroy_data, flags)
;; Returns the handler id (a gulong). destroy_data is a GClosureNotify fn ptr —
;; pass ffi/null. c_handler is the foreign-callable pointer.
(ffi/defcfn g-signal-connect-data "g_signal_connect_data"
  [:pointer :string :pointer :pointer :pointer :uint] :uint64)

(ffi/defcfn g-object-ref-sink "g_object_ref_sink" [:pointer] :pointer)
(ffi/defcfn g-object-unref    "g_object_unref"    [:pointer] :void)
