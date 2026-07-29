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
  (:require [clojure.string :as str]
            [glimmer.ffi :as g]
            [glimmer.genum :as genum]
            [hiccup2.core :as hiccup]
            [jolt.ffi :as ffi]))

;; --- value marshalling -------------------------------------------------------
(defn- ->bool [x] (if x 1 0))

(defn escape-markup
  "Escape `&`, `<`, `>` so `s` can be embedded safely inside Pango markup passed
  to a label's `:markup` prop. `&` is escaped first so the angle-bracket escapes
  are not themselves re-encoded."
  ^String [^String s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

;; --- Pango markup from hiccup data ------------------------------------------
;; Pango's text-attribute markup is a small XML subset (b, i, span, a, ...),
;; NOT HTML. Hiccup serializes vectors to a string and escapes content/attrs,
;; but it is HTML-flavoured — it will happily emit <div>, <br>, or a typo'd span
;; attribute, which gtk_label_set_markup then can't parse (the label falls back
;; to raw text or emits a GTK warning). So we validate the hiccup *data* against
;; Pango's vocabulary before handing it to hiccup for serialization: a bad
;; fragment fails loudly at the call site instead of rendering silently wrong.
;;
;; Attribute names mirror Pango's own (underscores: :font_family, :letter_spacing),
;; so what you write is exactly what Pango parses.
(def ^:private pango-tags
  "Pango markup vocabulary: tag -> the set of attributes it accepts, or nil when
  the tag takes no attributes."
  {:span #{:font_desc :font_family :face :size :style :weight :variant :stretch
           :foreground :background :alpha :underline :underline_color :rise
           :strikethrough :strikethrough_color :fallback :lang :letter_spacing
           :show :line_height :allow_breaks :insert_hyphens :text_transform
           :gravity :gravity_hint :overline :overline_color}
   :a    #{:href}
   :b nil :big nil :i nil :mark nil :s nil :small nil :sub nil :sup nil :tt nil
   :u nil})

(defn- markup-element? [form] (and (vector? form) (keyword? (first form))))

(declare markup-validate!)

(defn- markup-validate-element! [form]
  (let [tag     (first form)
        body    (rest form)
        attrs?  (map? (first body))
        attrs   (if attrs? (first body) nil)
        children (if attrs? (rest body) body)]
    (if-not (contains? pango-tags tag)
      (throw (ex-info (str "glimmer/markup: :" (name tag) " is not a Pango tag")
                      {:tag tag})))
    (let [allowed (get pango-tags tag)]
      (when (and attrs (seq attrs))
        (if (nil? allowed)
          (throw (ex-info (str "glimmer/markup: :" (name tag) " takes no attributes")
                          {:tag tag :attrs (keys attrs)}))
          (doseq [k (keys attrs)]
            (when-not (contains? allowed k)
              (throw (ex-info (str "glimmer/markup: :" (name k)
                                   " is not a :" (name tag) " attribute")
                              {:tag tag :attr k}))))))
      (run! markup-validate! children))))

(defn- markup-validate! [form]
  (cond
    (markup-element? form)  (markup-validate-element! form)
    (sequential? form)      (run! markup-validate! form)
    :else                   nil))

(defn markup
  "Render hiccup `form` to a Pango markup string for a label's :markup prop.

  [:span {:foreground \"#8e939d\"} \"Nothing to do yet\"]
  [:b [:i \"bold italic\"]]

  Serialization (escaping, seq expansion) is delegated to hiccup; the data is
  first validated against Pango's tag/attribute vocabulary, so an HTML-only tag
  (:div, :br) or a typo'd attribute (:forground) throws here rather than
  producing markup gtk_label_set_markup can't parse. Pango attribute names use
  underscores (:font_family, :letter_spacing) to match Pango's own spelling."
  [form]
  (markup-validate! form)
  (str (hiccup/html form)))

(defn markup-string
  "Coerce a label's :markup prop to a Pango markup string. A string passes through
  as-is (already markup); anything else is treated as hiccup and rendered via
  `markup` — so a [:label {:markup [:span ...]}] is validated and has its text
  escaped instead of the caller hand-rolling the XML."
  [m]
  (if (string? m) m (markup m)))

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
;; :hbox / :vbox are both GtkBox; the difference is orientation. normalize-tag
;; maps them to the :box spec, and with-orientation injects the matching
;; :orientation so a bare [:hbox ...] actually lays out horizontally (the box
;; ctor builds vertical by default and :apply corrects it). An explicit
;; :orientation in props always wins.
(def ^:private aliases {:hbox :box :vbox :box})

(def ^:private tag-orientation {:hbox :horizontal :vbox :vertical})

(defn- normalize-tag [tag] (get aliases tag tag))

(defn with-orientation
  "Inject the orientation implied by an :hbox/:vbox tag into its props, unless the
  caller already set :orientation. A no-op for any other tag."
  [tag props]
  (if-let [o (tag-orientation tag)]
    (if (contains? props :orientation) props (assoc props :orientation o))
    props))

;; --- signal registry ---------------------------------------------------------
;; event keyword -> GTK signal name. A handler has the GTK signature
;; void(widget, user_data); our callable ignores both args and invokes the
;; captured jolt handler, so one table covers every widget type. An atom so
;; extensions (glimmer-gl's :scale) can add events via register-signal!.
(def signals
  (atom {:on-click     "clicked"
         :on-change    "changed"
         :on-activate  "activate"
         :on-toggled   "toggled"}))

;; --- widget specs ------------------------------------------------------------
;; Each spec: {:ctor (fn [props] widget-ptr) :apply (fn [widget props]) :container (#{:box :window :none})}
;; The two suppressing setters live further down, beside the `suppressing` atom
;; they read; the :apply closures below call them, so declare them here. A
;; reference to a name that isn't interned yet is a compile error, in a nested
;; closure as much as at the top level.
(declare set-entry-text! set-checkbutton-active!)

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
            (when (contains? p :markup) (g/gtk-label-set-markup w (markup-string (:markup p))))
            (when (contains? p :xalign) (g/gtk-label-set-xalign w (:xalign p)))
            (when (contains? p :wrap)   (g/gtk-label-set-wrap w (->bool (:wrap p))))
            (when (contains? p :width-chars)     (g/gtk-label-set-width-chars w (:width-chars p)))
            (when (contains? p :max-width-chars) (g/gtk-label-set-max-width-chars w (:max-width-chars p)))
            (when (contains? p :lines)   (g/gtk-label-set-lines w (:lines p)))
            (when (contains? p :ellipsize) (g/gtk-label-set-ellipsize w (->enum "PangoEllipsizeMode" (:ellipsize p)))))
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
            (when (contains? p :active) (set-checkbutton-active! w (:active p))))
   :container :none})

(defn- separator-spec []
  {:ctor  (fn [_] (g/gtk-separator-new 0))   ; horizontal default; :apply sets the real orientation
   :apply (fn [w p] (when (contains? p :orientation) (g/gtk-orientable-set-orientation w (->orientation (:orientation p)))))
   :container :none})

(defn- frame-spec []
  {:ctor     (fn [p] (g/gtk-frame-new (or (:label p) "")))
   :apply    (fn [w p] (when (contains? p :label) (g/gtk-frame-set-label w (or (:label p) ""))))
   :container :frame})

(defn- scrolled-spec []
  ;; A single-child viewport. Built with natural-size propagation OFF so the
  ;; child scrolls inside the allotted area instead of forcing the window bigger.
  {:ctor     (fn [_]
               (let [sw (g/gtk-scrolled-window-new ffi/null ffi/null)]
                 (g/gtk-scrolled-window-set-propagate-natural-height sw 0)
                 (g/gtk-scrolled-window-set-propagate-natural-width sw 0)
                 sw))
   :apply    (fn [_ _])
   :container :scrolled})

;; hiccup tag -> widget spec. An atom so extensions register new widget types
;; (glimmer-gl's :gl-area, :scale) via register-widget! without editing this ns.
(def specs
  (atom {:window      (window-spec)
         :box         (box-spec)
         :button      (button-spec)
         :label       (label-spec)
         :entry       (entry-spec)
         :checkbutton (checkbutton-spec)
         :separator   (separator-spec)
         :frame       (frame-spec)
         :scrolled    (scrolled-spec)}))

(defn register-widget!
  "Register a widget spec under hiccup `tag`. A spec is
  {:ctor (fn [props] widget) :apply (fn [widget props]) :container kw
   :connect (fn [widget props])?}. :container is :none for a leaf, or :box /
   :window / :frame / :scrolled to reuse an existing child-management strategy.
  The optional :connect runs at create! time after the generic :on-* wiring, for
  widgets whose signals don't fit the uniform void(widget,data) shape (e.g. a
  GtkGLArea's realize/render/resize)."
  [tag spec] (swap! specs assoc tag spec) nil)

(defn- spec-for [tag] (@specs (normalize-tag tag)))

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

(defn release-callable!
  "Drop a reference previously held by `retain-callable!`, allowing collection
  once C no longer holds the pointer. Pair with one-shot foreign-callables (e.g.
  a g_idle_add source that returns FALSE) so a long-lived REPL session doesn't
  accumulate one retained closure per re-render."
  [cb] (swap! callables disj cb) cb)

;; Widgets whose signal we are currently firing ourselves (via a programmatic
;; setter — gtk_editable_set_text, gtk_check_button_set_active). connect-signals
;; gates handlers on this set, so a programmatic prop change can't feed back into
;; a reset!/re-render loop. GTK emits the signal synchronously during the setter,
;; so a plain conj-around-call-disj brackets exactly the spurious emission.
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

(defn- set-checkbutton-active!
  "Set a checkbutton's active state, but only when it differs from the widget's
  current state, and while suppressing the :on-toggled handler for the synchronous
  'toggled' emission gtk_check_button_set_active causes. Without this, a bulk
  update (e.g. 'complete all' flipping every task's :done) would re-render each
  row, set_active would fire 'toggled', and the row's handler would flip the task
  straight back."
  [widget active?]
  (let [target (->bool active?)]
    (when (not= target (g/gtk-checkbutton-get-active widget))
      (swap! suppressing conj widget)
      (g/gtk-checkbutton-set-active widget target)
      (swap! suppressing disj widget))))

;; Which signals carry a value the handler wants: GTK signal name -> (fn [widget] value).
;; An atom so an extension can register a value-bearing signal (e.g. a slider's
;; "value-changed" delivering the current double) without editing this table.
(def ^:private signal-value
  (atom {"changed" (fn [widget] (g/gtk-editable-get-text widget))}))

(defn register-signal!
  "Register an :on-* event key -> GTK signal name. With `value-fn` (a widget ->
  value fn) the handler is called with that value instead of zero args — used by
  value-bearing widgets (e.g. glimmer-gl's :scale slider). Lets extensions add
  widget events without editing glimmer.widget."
  ([event gtk-signal] (register-signal! event gtk-signal nil))
  ([event gtk-signal value-fn]
   (swap! signals assoc event gtk-signal)
   (when value-fn (swap! signal-value assoc gtk-signal value-fn))
   nil))

(defn connect-signals!
  "For every :on-* key in `props`, wrap its handler in a :collect-safe
  foreign-callable (GTK fires it from the blocking g_application_run loop) and
  connect it to the matching GTK signal on `widget`. Connected once at mount.

  Handlers are called with zero args — except :on-change, whose handler receives
  the entry's current text (read via gtk_editable_get_text)."
  [widget props]
  (doseq [[event handler] props]
    (when-let [signal (@signals event)]
      (let [value-fn (@signal-value signal)
            cb (ffi/foreign-callable
                 (fn [src-widget _data]
                   ;; skip emissions we triggered ourselves via a programmatic
                   ;; setter (see `suppressing`) so they can't loop back.
                   (when-not (contains? @suppressing src-widget)
                     (if value-fn (handler (value-fn src-widget)) (handler))))
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
  (when (contains? props :vexpand) (g/gtk-widget-set-vexpand widget (->bool (:vexpand props))))
  (when-let [w (:width-request props)]  (g/gtk-widget-set-size-request widget (int w) -1))
  (when-let [h (:height-request props)] (g/gtk-widget-set-size-request widget -1 (int h))))

;; --- public create / patch ---------------------------------------------------
(defn create!
  "Construct a fresh GTK widget for `tag`, apply `props`, and connect any :on-*
  handlers. Returns the widget pointer. Note: children are NOT added here — the
  reconciler appends them so it can reuse existing children across renders."
  [tag props]
  (let [props (with-orientation tag props)
        s (spec-for tag)
        widget ((:ctor s) props)]
    ((:apply s) widget props)
    (apply-widget-props! widget props)
    (connect-signals! widget props)
    ;; widgets whose signals don't fit the uniform void(widget,data) shape wire
    ;; them here (e.g. :gl-area's realize/render/resize). Runs once at mount.
    (when-let [connect (:connect s)] (connect widget props))
    widget))

(defn apply-props!
  "Re-apply the prop map to an existing widget (re-render path). Skips :on-* keys
  (signals stay wired from mount) and keys whose value is nil."
  [tag widget props]
  (let [applied (into {} (filter (fn [[k v]] (and (not (@signals k)) (some? v)))
                                 (with-orientation tag props)))]
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
    :scrolled (g/gtk-scrolled-window-set-child parent child)
    nil))

(defn remove-child!
  "Remove `child` from `parent`."
  [parent-tag parent child]
  (case (container-kind parent-tag)
    :box    (g/gtk-box-remove parent child)
    :window (g/gtk-window-set-child parent ffi/null)
    :frame  (g/gtk-frame-set-child parent ffi/null)
    :scrolled (g/gtk-scrolled-window-set-child parent ffi/null)
    nil))

(defn replace-child!
  "Replace `old-child` with `new-child` at the same position in `parent`."
  [parent-tag parent old-child new-child]
  (case (container-kind parent-tag)
    :box    (do (g/gtk-box-remove parent old-child)
                (g/gtk-box-append parent new-child))
    :window (g/gtk-window-set-child parent new-child)
    :frame  (g/gtk-frame-set-child parent new-child)
    :scrolled (g/gtk-scrolled-window-set-child parent new-child)
    nil))

(defn reorder-child!
  "Move `child` to sit immediately after `sibling` (nil = move to first position)
  within `parent`. Only GtkBox supports positional reordering; the single-child
  containers (window/frame/scrolled) no-op. Used by the keyed reconciler to fix
  widget order after reuse/create when survivors were reordered or a new item
  must precede an existing one."
  [parent-tag parent child sibling]
  (when (= :box (container-kind parent-tag))
    (g/gtk-box-reorder-child-after parent child (or sibling ffi/null))))
