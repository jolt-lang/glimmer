(ns glimmer.smoke
  "Non-interactive smoke test: mount the counter demo into a real GTK4 window and
  auto-quit after 1.2s. Exit 0 only if the whole pipeline (FFI load, app loop,
  widget mount, signal wiring, clean shutdown) ran without throwing."
  (:require [glimmer.counter :as counter]
            [glimmer.core :as ui]))

(defn -main [& _]
  (try
    (ui/run counter/counter
            :title "glimmer smoke" :width 320 :height 160
            :app-id "glimmer.smoke" :auto-quit-ms 1200)
    (println "SMOKE OK")
    (catch :default e
      (println "SMOKE FAIL:" e)
      (let [exit (resolve 'jolt.host/exit)]
        (when exit (exit 1))))))
