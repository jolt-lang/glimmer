(ns glimmer.test-runner
  "Entry point for `jolt -M:test`. Requires each glimmer test namespace and runs
  clojure.test against it. Prints a summary; exits non-zero if anything failed
  (so the :test task fails CI)."
  (:require [clojure.test :as t]))

;; Surface full causes on :error — the default report swallows the throwable.
(defmethod t/report :error [m]
  (t/with-test-out
    (t/inc-report-counter :error)
    (println "\nERROR in" (t/testing-vars-str m))
    (when (seq t/*testing-contexts*) (println (t/testing-contexts-str)))
    (when-let [message (:message m)] (println message))
    (when-let [e (:actual m)]
      (if (instance? Throwable e)
        (do (println "  ->" (.getName (class e)) ":" (ex-message e))
            (when-let [d (ex-data e)] (prn d))
            (when-let [c (ex-cause e)]
              (println "  caused by:" (.getName (class c)) ":" (ex-message c))))
        (prn e)))))

(defn- exit [code]
  ;; Prefer the host exit when present (jolt exits the process); fall back to no-op.
  (cond
    (resolve 'jolt.host/exit) ((resolve 'jolt.host/exit) code)
    (resolve 'System/exit)    ((resolve 'System/exit) code)
    :else nil))

(defn -main [& _]
  (let [namespaces '[glimmer.ratom-test glimmer.ratom-protocol-test
                     glimmer.backend-test glimmer.core-test glimmer.reconcile-test]]
    ;; a namespace that fails to load runs none of its tests, so it has to
    ;; count as a failure: otherwise the suite passes having skipped it
    (let [load-errors (count
                       (remove true?
                               (for [ns namespaces]
                                 (try (require ns :reload) true
                                      (catch Exception e
                                        (println "ERROR requiring" ns ":" (ex-message e)))))))
          results (apply t/run-tests namespaces)
          failed (+ (:fail results 0) (:error results 0) load-errors)]
      (println "----")
      (println "tests:" (:test results 0)
               "assertions:" (:pass results 0) "passed /"
               failed "failed")
      (when (pos? failed) (exit 1)))))
