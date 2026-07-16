(ns glimmer.main-thread-smoke
  "Regression smoke for the macOS main-thread rule.

  Mirrors the `joltc --nrepl-server` topology: the app's run() is invoked on a
  WORKER thread (as an nREPL eval would be) while the primordial thread runs
  jolt.host/run-main-pump. glimmer's run marshals g_application_run onto that
  main thread via call-on-main-thread — on macOS this is what stops the quartz
  backend from aborting when it sets the main menu off the main thread.

  Auto-quits after 1.2s. Exit 0 (printing SMOKE OK) = no crash."
  (:require [glimmer.core :as ui]
            [jolt.host :as host]))

(defn root []
  [:label "cross-thread smoke"])

(defn -main [& _]
  (let [result (promise)]
    ;; worker thread = stand-in for the nREPL eval future
    (future
      (try
        (ui/run root
                :title "glimmer cross-thread smoke"
                :width 240 :height 120
                :app-id "glimmer.main-thread-smoke"
                :auto-quit-ms 1200)
        (deliver result :ok)
        (catch :default e
          (deliver result [:fail e]))
        (finally
          (host/stop-main-pump))))
    ;; primordial thread owns the GUI main loop via the pump
    (host/run-main-pump)
    (let [r @result]
      (if (= r :ok)
        (println "SMOKE OK (cross-thread)")
        (do (println "SMOKE FAIL (cross-thread):" (ex-message (second r)))
            (when-let [exit (resolve 'jolt.host/exit)] (exit 1)))))))
