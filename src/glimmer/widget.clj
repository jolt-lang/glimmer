(ns glimmer.widget
  "Hiccup -> GTK4 widgets. A data-driven registry maps hiccup tags to widget
  constructors, prop maps to GTK setter calls, and :on-* event keys to GTK
  signals wired through foreign-callable. This layer creates/patches widgets and
  manages container children; the reconciler in glimmer.core drives when.

  Lifecycle: create! builds a widget (constructs it, applies props, connects any
  :on-* handlers). apply-props! re-applies the prop map to an existing widget on
  re-render. Signals are connected once at mount — handlers are expected to close
  over reactive cells (not values), so the first render's closure stays correct
  for the widget's life, exactly like reagent."
  (:require [glimmer.ffi :as g]
            [glimmer.genum :as genum]
            [jolt.ffi :as ffi]))

;; --- value marshalling -------------------------------------------------------
(defn- ->bool [x] (if x 1 0))

;; Resolve a property that may be a GEnum nick keyword (:start, :fill) OR a raw
;; integer. genum/enum returns nil when the type isn't live yet or the nick is
;; unknown; in that case fall back to the raw value (so callers can still pass
;; an explicit int). Applied in :apply, where the widget already exists and its
;; enum type is registered — so the common path resolves the nick.
(defn- ->enum [type-name x]
  (or (genum/enum type-name x) x))

;; GtkOrientation nicks are :horizontal / :vertical. Used by box & separator.
(defn- ->orientation [x] (->enum "GtkOrientation" x))

;; --- tag aliases (sugar) -----------------------------------------------------
(def ^:private aliases {:hbox :box :vbox :box})

(defn- normalize-tag [tag] (get aliases tag tag))

;; --- signal registry ---------------------------------------------------------
;; event keyword -> GTK signal name. A handler has the GTK signature
;; void(widget, user_data); our callable ignores both args and invokes the
;; captured jolt handler, so one table covers every widget type.
(def signals
  {:on-click     "clicked"
   :on-change    "changed"
   :on-activate  "activate"
   :on-toggled   "toggled"})

;; --- widget specs ------------------------------------------------------------
;; Each spec: {:ctor (fn [props] widget-ptr) :apply (fn [widget props]) :container (#{:box :window :none})}
(defn- window-spec []
  {:ctor    (fn [_] (g/gtk-window-new))
   :apply   (fn [w p]
              (when (:title p) (g/gtk-window-set-title w (:title p)))
              (when (or (:width p) (:height p))
                (g/gtk-window-set-default-size w (or (:width p) -1) (or (:height p) -1)))
              (g/gtk-widget-set-visible w (->bool (not (false? (:visible p))))))
   :container :window})

(defn- box-spec []
  {:ctor    (fn [p]
              ;; construct vertical by default; the real orientation is set in
              ;; :apply, by which point the box exists and GtkOrientation is
              ;; registered (the box installs the orientation property).
              (g/gtk-box-new 1 (or (:spacing p) 0)))
   :apply   (fn [w p]
              (when (contains? p :spacing)     (g/gtk-box-set-spacing w (:spacing p)))
              (when (contains? p :homogeneous) (g/gtk-box-set-homogeneous w (->bool (:homogeneous p))))
              (when (contains? p :orientation) (g/gtk-orientable-set-orientation w (->orientation (:orientation p)))))
   :container :box})

(defn- button-spec []
  {:ctor    (fn [p] (if (:label p) (g/gtk-button-new-with-label (:label p)) (g/gtk-button-new)))
   :apply   (fn [w p]
              (when (contains? p :label)   (g/gtk-button-set-label w (:label p)))
              (when (:tooltip p)           (g/gtk-widget-set-tooltip-text w (:tooltip p)))
              (when (contains? p :sensitive) (g/gtk-widget-set-sensitive w (->bool (:sensitive p)))))
   :container :none})

(defn- label-spec []
  {:ctor  (fn [p] (g/gtk-label-new (or (:label p) (:text p) "")))
   :apply (fn [w p]
            (when (contains? p :label)  (g/gtk-label-set-label w (:label p)))
            (when (contains? p :text)   (g/gtk-label-set-text w (:text p)))
            (when (contains? p :markup) (g/gtk-label-set-markup w (:markup p)))
            (when (contains? p :xalign) (g/gtk-label-set-xalign w (:xalign p))))
   :container :none})

(defn- entry-spec []
  {:ctor  (fn [_] (g/gtk-entry-new))
   :apply (fn [w p]
            (when (contains? p :text)        (set-entry-text! w (:text p)))
            (when (contains? p :placeholder) (g/gtk-editable-set-placeholder-text w (:placeholder p)))
            (when (contains? p :sensitive)   (g/gtk-widget-set-sensitive w (->bool (:sensitive p)))))
   :container :none})

(defn- checkbutton-spec []
  {:ctor  (fn [p] (if (:label p)
                    (g/gtk-checkbutton-new-with-label (:label p))
                    (g/gtk-checkbutton-new)))
   :apply (fn [w p]
            (when (contains? p :active) (g/gtk-checkbutton-set-active w (->bool (:active p)))))
   :container :none})

(defn- separator-spec []
  {:ctor  (fn [_] (g/gtk-separator-new 0))   ; horizontal default; :apply sets the real orientation
   :apply (fn [w p] (when (contains? p :orientation) (g/gtk-orientable-set-orientation w (->orientation (:orientation p)))))
   :container :none})

(defn- frame-spec []
  {:ctor     (fn [p] (g/gtk-frame-new (or (:label p) "")))
   :apply    (fn [w p] (when (contains? p :label) (g/gtk-frame-set-label w (or (:label p) ""))))
   :container :frame})

(def ^:private specs
  {:window      (window-spec)
   :box         (box-spec)
   :button      (button-spec)
   :label       (label-spec)
   :entry       (entry-spec)
   :checkbutton (checkbutton-spec)
   :separator   (separator-spec)
   :frame       (frame-spec)})

(defn- spec-for [tag] (specs (normalize-tag tag)))

(defn container-kind
  "How a tag holds children: :box (ordered append/remove), :window (single child),
  or :none (leaf)."
  [tag] (:container (spec-for tag)))

;; --- callable registry (keep signal callbacks alive for the widget's life) ----
;; foreign-callable retains its closure until free-callable, but we also hold
;; strong references here as a belt-and-suspenders against any GC edge.
(def ^:private callables (atom #{}))

(defn retain-callable!
  "Hold a strong reference to a foreign-callable pointer for the process lifetime
  so it isn't collected while C (GTK) still holds only the raw pointer."
  [cb] (swap! callables conj cb) cb)

;; Widgets whose "changed" signal we are currently firing ourselves (via
;; gtk_editable_set_text). The :on-change handler ignores emissions for these, so
;; a programmatic set_text can't feed back into a reset!/re-render loop. GTK emits
;; "changed" synchronously during set_text, so a plain add-around-call-disj works.
(def ^:private suppressing (atom #{}))

(defn- set-entry-text!
  "Set an entry's text, but only when it differs from the current text, and while
  suppressing the :on-change handler for the synchronous 'changed' emission this
  causes. Avoids the set_text -> on-change -> reset! -> re-render -> set_text loop."
  [widget text]
  (when (and (some? text) (not= text (g/gtk-editable-get-text widget)))
    (swap! suppressing conj widget)
    (g/gtk-editable-set-text widget text)
    (swap! suppressing disj widget)))

;; Which signals carry a value the handler wants: GTK signal name -> (fn [widget] value)
(def ^:private signal-value
  {"changed" (fn [widget] (g/gtk-editable-get-text widget))})

(defn connect-signals!
  "For every :on-* key in `props`, wrap its handler in a :collect-safe
  foreign-callable (GTK fires it from the blocking g_application_run loop) and
  connect it to the matching GTK signal on `widget`. Connected once at mount.

  Handlers are called with zero args — except :on-change, whose handler receives
  the entry's current text (read via gtk_editable_get_text)."
  [widget props]
  (doseq [[event handler] props]
    (when-let [signal (signals event)]
      (let [value-fn (signal-value signal)
            cb (ffi/foreign-callable
                 (fn [src-widget _data]
                   (if value-fn (handler (value-fn src-widget)) (handler)))
                 [:pointer :pointer] :void :collect-safe)]
        (swap! callables conj cb)
        (g/g-signal-connect-data widget signal cb ffi/null ffi/null g/CONNECT-DEFAULT)))))

;; --- universal GtkWidget props (apply to every widget, every tag) ------------
;; Margins, alignment, expand — GtkWidget-level props that aren't specific to any
;; tag, so they're applied to all of them in addition to the tag's own :apply.
;; :halign/:valign take a GtkAlign nick (:start :end :center :fill :baseline...)
;; resolved at runtime by glimmer.genum — no constant table.
(defn apply-widget-props!
  [widget props]
  (when-let [m (:margin props)]
    (g/gtk-widget-set-margin-start widget m)
    (g/gtk-widget-set-margin-end widget m)
    (g/gtk-widget-set-margin-top widget m)
    (g/gtk-widget-set-margin-bottom widget m))
  (when-let [m (:margin-start props)]   (g/gtk-widget-set-margin-start widget m))
  (when-let [m (:margin-end props)]     (g/gtk-widget-set-margin-end widget m))
  (when-let [m (:margin-top props)]     (g/gtk-widget-set-margin-top widget m))
  (when-let [m (:margin-bottom props)]  (g/gtk-widget-set-margin-bottom widget m))
  (when-let [a (:halign props)] (g/gtk-widget-set-halign widget (->enum "GtkAlign" a)))
  (when-let [a (:valign props)] (g/gtk-widget-set-valign widget (->enum "GtkAlign" a)))
  (when (contains? props :hexpand) (g/gtk-widget-set-hexpand widget (->bool (:hexpand props))))
  (when (contains? props :vexpand) (g/gtk-widget-set-vexpand widget (->bool (:vexpand props)))))

;; --- public create / patch ---------------------------------------------------
(defn create!
  "Construct a fresh GTK widget for `tag`, apply `props`, and connect any :on-*
  handlers. Returns the widget pointer. Note: children are NOT added here — the
  reconciler appends them so it can reuse existing children across renders."
  [tag props]
  (let [s (spec-for tag)
        widget ((:ctor s) props)]
    ((:apply s) widget props)
    (apply-widget-props! widget props)
    (connect-signals! widget props)
    widget))

(defn apply-props!
  "Re-apply the prop map to an existing widget (re-render path). Skips :on-* keys
  (signals stay wired from mount) and keys whose value is nil."
  [tag widget props]
  (let [applied (into {} (filter (fn [[k v]] (and (not (signals k)) (some? v))) props))]
    ((:apply (spec-for tag)) widget applied)
    (apply-widget-props! widget applied)))

(defn show!
  "Make a widget visible. GTK4 widgets default to visible, but setting it
  explicitly is harmless and makes :visible false work."
  [widget props]
  (g/gtk-widget-set-visible widget (->bool (not (false? (:visible props))))))

;; --- container child management ----------------------------------------------
(defn append-child!
  "Add `child` to the end of `parent`. Dispatches on the parent's container kind."
  [parent-tag parent child]
  (case (container-kind parent-tag)
    :box    (g/gtk-box-append parent child)
    :window (g/gtk-window-set-child parent child)
    :frame  (g/gtk-frame-set-child parent child)
    nil))

(defn remove-child!
  "Remove `child` from `parent`."
  [parent-tag parent child]
  (case (container-kind parent-tag)
    :box    (g/gtk-box-remove parent child)
    :window (g/gtk-window-set-child parent ffi/null)
    :frame  (g/gtk-frame-set-child parent ffi/null)
    nil))

(defn replace-child!
  "Replace `old-child` with `new-child` at the same position in `parent`."
  [parent-tag parent old-child new-child]
  (case (container-kind parent-tag)
    :box    (do (g/gtk-box-remove parent old-child)
                (g/gtk-box-append parent new-child))
    :window (g/gtk-window-set-child parent new-child)
    :frame  (g/gtk-frame-set-child parent new-child)
    nil))
