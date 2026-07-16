(ns glimmer.repl-live-smoke
  "Regression smoke for live GUI development over nREPL.

  A label is bound to a ratom. A WORKER thread (standing in for an nREPL eval on
  its worker thread) mutates the ratom while the app is running. The reactive
  re-render must hop onto the GTK main loop via a one-shot g_idle_add source
  (gui-loop-running? marshalling in glimmer.core), NOT reconcile inline on the
  worker thread — on macOS that off-main-thread GTK mutation aborts under AppKit.

  Marshalling is confirmed two ways:
    1. no crash (exit 0) — without marshalling, the worker-thread re-render
       touches GTK off the main thread and AppKit aborts; and
    2. after the loop quits, the worker's mutation is reflected in a render
       (the deferred main-loop re-render applied it).

  Auto-quits and exits 0 printing SMOKE OK; non-zero on failure."
  (:require [glimmer.core :as ui]
            [glimmer.ratom :as r]
            [jolt.host :as host]))

(def state (r/atom "initial"))
(def observed (atom "initial"))           ; value seen by the most recent render

(defn root []
  (let [v @state]
    (reset! observed v)
    [:label {:label v}]))

(defn- fail [msg]
  (println "SMOKE FAIL (repl-live):" msg)
  (let [exit (resolve 'jolt.host/exit)] (when exit (exit 1))))

(defn -main [& _]
  (let [result (promise)]
    ;; Thread A — stand-in for the REPL thread that launched the app. ui/run
    ;; marshals startup onto the main thread and blocks here until it quits.
    (future
      (try
        (ui/run root :title "glimmer repl-live smoke"
                   :width 260 :height 120
                   :app-id "glimmer.repl-live-smoke"
                   :auto-quit-ms 1800)
        (deliver result :ok)
        (catch :default e (deliver result [:crashed (.getMessage e)])
                         (host/stop-main-pump))
        (finally (host/stop-main-pump))))
    ;; Thread B — stand-in for an nREPL eval mutating reactive state off the main
    ;; thread. Give the app time to mount + enter g_application_run first.
    (future
      (Thread/sleep 600)
      (reset! state "updated-from-worker"))
    ;; Main thread owns the GUI loop via the pump.
    (host/run-main-pump)
    (let [r @result]
      (cond
        (not= r :ok)                (fail (str "app did not exit cleanly: " r))
        (not= "updated-from-worker" @observed)
        (fail (str "worker mutation never reached a render; observed=" @observed))
        :else                       (println "SMOKE OK (repl-live)")))))
