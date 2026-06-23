(ns glimmer.reactivity-smoke
  "Non-interactive proof that glimmer's reactivity works against the live GTK4
  loop. Mounts a Form-1 component whose render increments a counter; a GTK
  timeout then swaps the reactive cell the component reads. The component must
  re-render exactly once (render-count goes 1 -> 2). Run via the :smoke task;
  requires a display. Exits non-zero on failure."
  (:require [glimmer.ratom :refer [atom]]
            [glimmer.core :as ui]
            [glimmer.widget :as w]
            [glimmer.ffi :as g]
            [jolt.ffi :as ffi]))

(def n (atom 0))
(def render-count (atom 0))
(def result (atom :pending))

(defn app []
  (swap! render-count inc)
  [:label {:label (str "n=" @n)}])

(defn- quitter [app-obj]
  ;; Decide pass/fail at fire time (not schedule time) — render-count only grows
  ;; once the swap re-renders, which happens after this is scheduled.
  (w/retain-callable!
    (ffi/foreign-callable
      (fn [_]
        (reset! result (if (>= @render-count 2) :pass :fail))
        (g/g-application-quit app-obj) 0)
      [:pointer] :int :collect-safe)))

(defn -main [& _]
  (try
    (let [app-obj (g/gtk-application-new "glimmer.reactivity" g/APPLICATION-DEFAULT-FLAGS)
          activate (fn [_ _]
                     (let [win (g/gtk-application-window-new app-obj)]
                       (g/gtk-window-set-title win "reactivity smoke")
                       (g/gtk-window-set-default-size win 240 120)
                       (ui/mount win :window [app])            ; render-count -> 1
                       (g/gtk-window-present win)
                       (g/g-timeout-add 400 (w/retain-callable!
                                              (ffi/foreign-callable
                                                (fn [_] (swap! n inc) 0)
                                                [:pointer] :int :collect-safe))
                                          ffi/null)
                       (g/g-timeout-add 900 (quitter app-obj) ffi/null)))
          activate-cb (w/retain-callable!
                        (ffi/foreign-callable activate [:pointer :pointer] :void :collect-safe))]
      (g/g-signal-connect-data app-obj "activate" activate-cb ffi/null ffi/null g/CONNECT-DEFAULT)
      (g/g-application-run app-obj 0 ffi/null)
      (prn :smoke :result @result :render-count @render-count)
      (when (not= :pass @result)
        (let [exit (resolve 'jolt.host/exit)] (when exit (exit 1)))))
    (catch :default e
      (prn :smoke-error e)
      (let [exit (resolve 'jolt.host/exit)] (when exit (exit 1))))))
