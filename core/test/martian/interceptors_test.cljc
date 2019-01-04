(ns martian.interceptors-test
  (:require [martian.interceptors :as i]
            [martian.encoders :as encoders]
            [tripod.context :as tc]
            [schema.core :as s]
            #?(:clj [clojure.test :refer [deftest is testing are]]
               :cljs [cljs.test :refer-macros [deftest testing is are]])
            #?(:clj [martian.test-utils :as tu])))

(deftest encode-body-test
  (let [i i/default-encode-body
        body {:the {:wheels ["on" "the" "bus"]}
              :go {:round {:and "round"}}}]
    (testing "json"
      (is (= {:body (encoders/json-encode body)
              :headers {"Content-Type" "application/json"}}
             (:request ((:enter i) {:request {:body body}
                                    :handler {:consumes ["application/json"]}})))))

    (testing "edn"
      (is (= {:body (pr-str body)
              :headers {"Content-Type" "application/edn"}}
             (:request ((:enter i) {:request {:body body}
                                    :handler {:consumes ["application/edn"]}})))))

    (testing "transit"
      (testing "+json"
        (is (= {:body body
                :headers {"Content-Type" "application/transit+json"}}
               (-> ((:enter i) {:request {:body body}
                                :handler {:consumes ["application/transit+json"]}})
                   :request
                   (update :body #?(:clj (comp #(encoders/transit-decode % :json)
                                               tu/input-stream->byte-array)
                                    :cljs #(encoders/transit-decode % :json)))))))

      #?(:clj
         (testing "+msgpack"
           (is (= {:body body
                   :headers {"Content-Type" "application/transit+msgpack"}}
                  (-> ((:enter i) {:request {:body body}
                                   :handler {:consumes ["application/transit+msgpack"]}})
                      :request
                      (update :body (comp #(encoders/transit-decode % :msgpack)
                                          tu/input-stream->byte-array))))))))))

(defn- stub-response [content-type body]
  {:name ::stub-response
   :enter (fn [ctx]
            (assoc ctx :response {:body body
                                  :headers {:content-type content-type}}))})

(deftest coerce-response-test
  (let [i i/default-coerce-response
        body {:the {:wheels ["on" "the" "bus"]}
              :go {:round {:and "round"}}}]

    (testing "json"
      (let [ctx (tc/enqueue* {} [i (stub-response "application/json" (encoders/json-encode body))])]
        (is (= body
               (-> (tc/execute ctx) :response :body)))))

    (testing "edn"
      (let [ctx (tc/enqueue* {} [i (stub-response "application/edn" (pr-str body))])]
        (is (= body
               (-> (tc/execute ctx) :response :body)))))

    (testing "transit"
      (testing "+json"
        (let [ctx (tc/enqueue* {} [i (stub-response "application/transit+json"
                                                    #?(:clj (slurp (encoders/transit-encode body :json))
                                                       :cljs (encoders/transit-encode body :json)))])]
          (is (= body
                 (-> (tc/execute ctx) :response :body)))))

      #?(:clj
         (testing "+msgpack"
           (let [ctx (tc/enqueue* {} [i (stub-response "application/transit+msgpack"
                                                       (tu/input-stream->byte-array (encoders/transit-encode body :msgpack)))])]
             (is (= body
                    (-> (tc/execute ctx) :response :body)))))))))

(deftest custom-encoding-test
  (testing "a user can support an encoding that martian doesn't know about by default"
    (let [reverse-string #(apply str (reverse %))
          encoders (assoc (encoders/default-encoders)
                          "text/magical+json" {:encode (comp reverse-string encoders/json-encode)
                                               :decode (comp #(encoders/json-decode % keyword) reverse-string)
                                               :as :magic})
          body {:the {:wheels ["on" "the" "bus"]}
                :go {:round {:and "round"}}}
          encoded-body (-> body encoders/json-encode reverse-string)]

      (let [ctx (tc/enqueue* {:request {:body body}
                              :handler {:consumes ["text/magical+json"]
                                        :produces ["text/magical+json"]}}
                             [(i/encode-body encoders)
                              (i/coerce-response encoders)
                              (stub-response "text/magical+json" encoded-body)])
            result (tc/execute ctx)]

        (is (= {:body encoded-body
                :headers {"Content-Type" "text/magical+json"
                          "Accept" "text/magical+json"}
                :as :magic}
               (:request result)))

        (is (= {:body body
                :headers {:content-type "text/magical+json"}}
               (:response result)))))))

(deftest auto-encoder-test
  (testing "when the server speaks a language martian doesn't understand it leaves it alone"
    (let [reverse-string #(apply str (reverse %))
          body {:the {:wheels ["on" "the" "bus"]}
                :go {:round {:and "round"}}}
          encoded-body (-> body encoders/json-encode reverse-string)]

      (let [ctx (tc/enqueue* {:request {:body encoded-body
                                        :headers {"Content-Type" "text/magical+json"
                                                  "Accept" "text/magical+json"}}
                              :handler {:consumes ["text/magical+json"]
                                        :produces ["text/magical+json"]}}
                             [i/default-encode-body
                              i/default-coerce-response
                              (stub-response "text/magical+json" encoded-body)])
            result (tc/execute ctx)]

        (is (= {:body encoded-body
                :headers {"Content-Type" "text/magical+json"
                          "Accept" "text/magical+json"}
                :as :auto}
               (:request result)))

        (is (= {:body encoded-body
                :headers {:content-type "text/magical+json"}}
               (:response result)))))))

(deftest query-params-test
  (let [i i/set-query-params
        ctx {:request {}
             :params {:foo [1 2 3]}
             :handler {:query-schema {:foo [s/Int]}}}
        result (fn [collection-format]
                 (->> (assoc-in ctx [:handler :query-collection-formats :foo] collection-format)
                      ((:enter i))
                      :request
                      :query-params
                      :foo))]

  (are [fmt expected]
      (= expected (result fmt))

    nil [1 2 3]
    :csv "1,2,3"
    :ssv "1 2 3"
    :tsv "1\t2\t3"
    :pipes "1|2|3")))
