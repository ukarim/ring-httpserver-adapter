(ns ring.adapter.httpserver
  "A Ring adapter for jdk's com.sun.net.httpserver.HttpServer"
  (:import [com.sun.net.httpserver
            Headers
            HttpServer
            HttpHandler
            HttpExchange]
           [java.net InetSocketAddress]
           [java.util.concurrent Executors]
           [java.util
            List
            Map$Entry])
  (:require [ring.core.protocols :as protocols]
            [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn- build-request-headers
  [^Headers headers]
  (persistent!
    (reduce
      (fn[acc ^Map$Entry entry]
        (assoc! acc 
          (.toLowerCase ^String (.getKey entry))
          (str/join "," (iterator-seq (.iterator ^List (.getValue entry))))))
      (transient {})
      headers)))

(defn- build-response-headers
  [headers-map]
  (persistent!
    (reduce-kv
      (fn [acc k v]
        (if (list? v)
          (assoc! acc k v)
          (assoc! acc k (conj '() v))))
      (transient {})
      headers-map)))

(defn- build-request-map
  [^HttpExchange exchange]
  {:server-port    (-> exchange .getLocalAddress .getPort)
   :server-name    (-> exchange .getLocalAddress .getHostName)
   :remote-addr    (-> exchange .getRemoteAddress .getAddress)
   :uri            (-> exchange .getRequestURI .toString)
   :query-string   (-> exchange .getRequestURI .getQuery)
   :scheme         :http
   :request-method (-> exchange .getRequestMethod)
   :protocol       (-> exchange .getProtocol)
   :headers        (build-request-headers (.getRequestHeaders exchange))
   :body           (.getRequestBody exchange)})

(defn- set-exchange-response
  [^HttpExchange exchange {:keys [status headers body :as resp-map]}]
  (.putAll (.getResponseHeaders exchange) (build-response-headers headers))
  (.sendResponseHeaders exchange status 0) ; use chunked transfer encoding
  (protocols/write-body-to-stream body resp-map (.getResponseBody exchange)))

(defn- ^HttpHandler create-http-handler
  [handler]
  (reify
    HttpHandler
    (handle [_ exchange]
      (let [request-map (build-request-map exchange)
            response-map (handler request-map)]
        (set-exchange-response exchange response-map)))))

(defn ^HttpServer run-httpserver
  [handler {:keys [port]
            :or {port 8080}
            :as options}]
  (let [server (HttpServer/create)]
    (.bind server (InetSocketAddress. port) 0)
    (.createContext server "/" (create-http-handler handler))
    (.setExecutor server (Executors/newFixedThreadPool (:threads options 8)))
    (.start server)
    server))
