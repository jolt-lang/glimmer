(ns glimmer.core
  "The component model and reconciler for glimmer.

  Components return hiccup — vectors of [:tag props? children...] where children
  may themselves be native elements ([keyword ...]) or component invocations
  ([fn args...]). Strings/numbers become labels. nil children are skipped.

  Two component shapes, as in reagent:
    Form-1: (defn greeting [name] [:label {:label (str \"hi \" name)}])
            — called fresh on every render.
    Form-2: (defn counter []
              (let [n (atom 0)]            ; local state, created once
                (fn [] [:button {:label (str @n) :on-click #(swap! n inc)}])))
            — the outer fn runs once (on mount); the returned fn renders.

  Reconciling is positional: at each child position the existing widget is reused
  when the tag matches (props re-applied) and replaced when it doesn't; surplus
  children are removed and new ones appended. Components are transparent — they
  produce no widget of their own, just expand to native elements — but each one
  binds glimmer.ratom/*current-watcher* around its render so any reactive cell it
  derefs re-runs that component (and only that component) on change.

  The app loop is a GtkApplication whose :activate handler mounts the root
  component into a fresh window; g_application_run blocks running the GTK main
  loop, and every signal/activate callback is a :collect-safe foreign-callable."
  (:require [glimmer.ffi :as g]
            [glimmer.ratom :as r]
            [glimmer.widget :as w]
            [jolt.ffi :as ffi]))

;; --- hiccup parsing ----------------------------------------------------------
(defn- parse-native [v]
  (let [tag (first v) body (next v)]
    (if (and (seq body) (map? (first body)))
      {:tag tag :props (first body) :children (vec (rest body))}
      {:tag tag :props {} :children (vec body)})))

;; --- instance tree -----------------------------------------------------------
;; Each rendered element has an atom holding its instance state:
;;   native: {:type :native :tag :button :props {} :widget <ptr> :children [atom...]}
;;   comp:   {:type :comp :render-fn <fn> :children [atom]}  ; one child = expanded root
;; A nil instance means nothing has been mounted at that position yet.

(declare reconcile-el!)

(defn- destroy-inst!
  "Remove an instance's widget from its parent and recursively destroy children."
  [inst-atom parent-widget parent-tag]
  (let [inst @inst-atom
        widget (:widget inst)]
    (when widget
      (w/remove-child! parent-tag parent-widget widget))
    (doseq [child (:children inst)]
      (destroy-inst! child widget (:tag inst)))))

(defn- reconcile-children!
  "Positionally reconcile `new-children` (hiccup) against the instance's existing
  children, reusing widgets where the element shape matches at a position."
  [parent-widget parent-tag new-children inst-atom]
  (let [old (:children @inst-atom)
        n (count new-children)
        ;; reuse the child atom at each position when it exists; else a fresh one
        reused (vec (for [i (range n)]
                      (if (< i (count old)) (nth old i) (atom nil))))]
    ;; remove widgets for children that no longer exist
    (doseq [i (range n (count old))]
      (destroy-inst! (nth old i) parent-widget parent-tag))
    ;; reconcile each child into its (possibly new) atom
    (doseq [i (range n)]
      (reconcile-el! parent-widget parent-tag (nth new-children i) (nth reused i)))
    (swap! inst-atom assoc :children reused)))

(defn- reconcile-native!
  [parent-widget parent-tag v inst-atom]
  (let [{:keys [tag props children]} (parse-native v)
        inst @inst-atom]
    (if (and inst (= (:type inst) :native) (= (:tag inst) tag))
      (do (w/apply-props! tag (:widget inst) props)
          (swap! inst-atom assoc :props props))
      (let [widget (w/create! tag props)
            new {:type :native :tag tag :props props :widget widget :children []}]
        (if (and inst (= (:type inst) :native))
          (w/replace-child! parent-tag parent-widget (:widget inst) widget)
          (w/append-child! parent-tag parent-widget widget))
        (reset! inst-atom new)))
    (reconcile-children! (:widget @inst-atom) tag children inst-atom)))

(defn- reconcile-comp!
  "Expand a component invocation into native hiccup and reconcile its single child.
  Each component renders exactly once per render: a Form-2 component's outer fn
  runs only on mount (its returned render fn is cached); a Form-1 component's fn
  is re-applied each render. We classify with fn?, not ifn? — a hiccup vector is
  callable, so ifn? would treat every Form-1 result as a Form-2 render fn."
  [parent-widget parent-tag v inst-atom]
  (let [f (first v) args (rest v)
        cached-render-fn (:render-fn @inst-atom)]
    (swap! inst-atom (fn [cur] (if (:children cur) cur (assoc cur :children [(atom nil)]))))
    (let [child-atom (first (:children @inst-atom))
          ;; subscribe: any reactive read during render re-runs just this component
          hiccup (binding [r/*current-watcher* (fn [_] (reconcile-comp! parent-widget parent-tag v inst-atom))]
                   (if cached-render-fn
                     (cached-render-fn)                       ; Form-2: cached render fn
                     (let [result (apply f args)]             ; first invocation
                       (if (fn? result)
                         (do (swap! inst-atom assoc :type :comp :render-fn result) (result))
                         result))))]                          ; Form-1: hiccup, used directly
      (reconcile-el! parent-widget parent-tag hiccup child-atom))))

(defn- reconcile-el!
  "Reconcile one hiccup element (native, component, or primitive) into inst-atom."
  [parent-widget parent-tag el inst-atom]
  (cond
    (nil? el) nil
    (string? el) (reconcile-native! parent-widget parent-tag [:label {:label el}] inst-atom)
    (number? el) (reconcile-native! parent-widget parent-tag [:label {:label (str el)}] inst-atom)
    (vector? el)
    (let [head (first el)]
      (cond
        (keyword? head) (reconcile-native! parent-widget parent-tag el inst-atom)
        (fn? head)      (reconcile-comp! parent-widget parent-tag el inst-atom)
        :else (throw (ex-info (str "unsupported hiccup element: " (pr-str el)) {:el el}))))
    :else (reconcile-native! parent-widget parent-tag [:label {:label (pr-str el)}] inst-atom)))

;; --- public API --------------------------------------------------------------
(defn mount
  "Mount `hiccup` (a native element, a component invocation [fn args...], or a
  primitive) into a GTK container. Returns the root instance atom. Wrap a plain
  function in a vector ([my-app]) so it reconciles as a reactive component."
  [container-widget container-tag hiccup]
  (let [root (atom nil)]
    (reconcile-el! container-widget container-tag hiccup root)
    root))

(defn unmount!
  "Remove a mounted root (the atom returned by mount) from its container."
  [root-inst-atom container-widget container-tag]
  (destroy-inst! root-inst-atom container-widget container-tag)
  (reset! root-inst-atom nil))

(defn run
  "Run a GTK4 application. On :activate, a window is created and `root-fn` (a
  thunk returning hiccup) is mounted into it as a reactive component. Blocks
  until the app quits.

  Options:
    :app-id        GApplication id      (default \"glimmer.app\")
    :title         window title         (default \"glimmer\")
    :width         window width in px   (default 400)
    :height        window height in px  (default 300)
    :auto-quit-ms  if set, quit the loop after this many milliseconds
                   (smoke/automated tests)."
  [root-fn & {:keys [app-id title width height auto-quit-ms]
              :or {app-id "glimmer.app" title "glimmer" width 400 height 300}
              :as opts}]
  (let [app (g/gtk-application-new app-id g/APPLICATION-DEFAULT-FLAGS)
        activate (fn [_app _data]
                   (let [win (g/gtk-application-window-new app)]
                     (g/gtk-window-set-title win title)
                     (g/gtk-window-set-default-size win width height)
                     ;; mount as a component so the whole tree is reactive
                     (mount win :window [root-fn])
                     (g/gtk-window-present win)
                     (when auto-quit-ms
                       (let [quit (ffi/foreign-callable
                                    (fn [_data] (g/g-application-quit app) 0)
                                    [:pointer] :int :collect-safe)]
                         (w/retain-callable! quit)
                         (g/g-timeout-add auto-quit-ms quit ffi/null)))))
        activate-cb (ffi/foreign-callable activate [:pointer :pointer] :void :collect-safe)]
    (w/retain-callable! activate-cb)
    (g/g-signal-connect-data app "activate" activate-cb ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-application-run app 0 ffi/null)))
