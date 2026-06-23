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
;; GtkOrientation
(def ORIENTATION-HORIZONTAL 0)
(def ORIENTATION-VERTICAL   1)

;; GApplicationFlags — 0 is G_APPLICATION_DEFAULT_FLAGS
(def APPLICATION-DEFAULT-FLAGS 0)

;; GConnectFlags — 0 is no flags (behaves like g_signal_connect)
(def CONNECT-DEFAULT 0)

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

;; --- widgets -----------------------------------------------------------------
(ffi/defcfn gtk-button-new              "gtk_button_new"              [] :pointer)
(ffi/defcfn gtk-button-new-with-label   "gtk_button_new_with_label"   [:string] :pointer)
(ffi/defcfn gtk-button-set-label        "gtk_button_set_label"        [:pointer :string] :void)

(ffi/defcfn gtk-label-new               "gtk_label_new"               [:string] :pointer)
(ffi/defcfn gtk-label-set-text          "gtk_label_set_text"          [:pointer :string] :void)
(ffi/defcfn gtk-label-set-label         "gtk_label_set_label"         [:pointer :string] :void)

(ffi/defcfn gtk-entry-new               "gtk_entry_new"               [] :pointer)
;; GtkEditable interface (implemented by GtkEntry):
(ffi/defcfn gtk-editable-get-text       "gtk_editable_get_text"       [:pointer] :string)
(ffi/defcfn gtk-editable-set-text       "gtk_editable_set_text"       [:pointer :string] :void)

(ffi/defcfn gtk-checkbutton-new               "gtk_check_button_new"               [] :pointer)
(ffi/defcfn gtk-checkbutton-new-with-label    "gtk_check_button_new_with_label"    [:string] :pointer)
(ffi/defcfn gtk-checkbutton-set-active        "gtk_check_button_set_active"        [:pointer :int] :void)
(ffi/defcfn gtk-checkbutton-get-active        "gtk_check_button_get_active"        [:pointer] :int)

(ffi/defcfn gtk-separator-new           "gtk_separator_new"           [:int] :pointer)

;; --- generic widget state ----------------------------------------------------
(ffi/defcfn gtk-widget-set-visible    "gtk_widget_set_visible"    [:pointer :int] :void)
(ffi/defcfn gtk-widget-set-sensitive  "gtk_widget_set_sensitive"  [:pointer :int] :void)
(ffi/defcfn gtk-widget-set-tooltip-text "gtk_widget_set_tooltip_text" [:pointer :string] :void)

;; --- signals & reference counting (libgobject) -------------------------------
;; g_signal_connect_data(instance, detailed_signal, c_handler, data, destroy_data, flags)
;; Returns the handler id (a gulong). destroy_data is a GClosureNotify fn ptr —
;; pass ffi/null. c_handler is the foreign-callable pointer.
(ffi/defcfn g-signal-connect-data "g_signal_connect_data"
  [:pointer :string :pointer :pointer :pointer :uint] :uint64)

(ffi/defcfn g-object-ref-sink "g_object_ref_sink" [:pointer] :pointer)
(ffi/defcfn g-object-unref    "g_object_unref"    [:pointer] :void)
