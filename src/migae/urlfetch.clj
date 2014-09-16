(ns migae.urlfetch
  (:import [com.google.appengine.api.urlfetch
            URLFetchServiceFactory
            FetchOptions
            FetchOptions$Builder
            HTTPHeader
            HTTPRequest
            HTTPMethod])
  (:require [clojure.tools.logging :as log]))

;; original code from appengine-magic

(defonce ^{:dynamic true} *urlfetch-service* (atom nil))

(defn get-urlfetch-service []
  (do (when (nil? @*urlfetch-service*)
	(reset! *urlfetch-service* (URLFetchServiceFactory/getURLFetchService)))
      @*urlfetch-service*))

(defn- urlify [url] (if (string? url) (java.net.URL. url) url))

(defrecord HTTPResponse [content
			 final-url
			 headers
			 response-code])

(defn- parse-headers [headers]
  (zipmap (map #(.getName %) headers)
	  (map #(.getValue %) headers)))

(defn- make-headers [header-map]
  (map
   (fn [[name-key val]]
     (HTTPHeader. (if (keyword? name-key)
                      (name name-key)
                      (str name-key))
                  val))
   header-map))

(defn- parse-response
  [^com.google.appengine.api.urlfetch.HTTPResponse r]
  (HTTPResponse. (.getContent r)
		 (.getFinalUrl r)
		 (parse-headers (.getHeaders r))
		 (.getResponseCode r)))

(defn make-request
  [url &
   {:keys [method headers payload allow-truncate follow-redirects deadline]
    :or {method :get
         headers {}
         payload nil
         allow-truncate FetchOptions/DEFAULT_ALLOW_TRUNCATE
         follow-redirects FetchOptions/DEFAULT_FOLLOW_REDIRECTS
         deadline FetchOptions/DEFAULT_DEADLINE}}]
  (let [fetch-options (FetchOptions$Builder/withDefaults)]
    (if allow-truncate
      (.allowTruncate fetch-options)
      (.disallowTruncate fetch-options))
    (if follow-redirects
      (.followRedirects fetch-options)
      (.doNotFollowRedirects fetch-options))
    (when-not (nil? deadline)
      (.setDeadline fetch-options (double deadline)))
    (let [method-obj (method {:delete HTTPMethod/DELETE
                              :get    HTTPMethod/GET
                              :head   HTTPMethod/HEAD
                              :post   HTTPMethod/POST
                              :put    HTTPMethod/PUT})
          request    (HTTPRequest. url method-obj fetch-options)]
      (doseq [h (make-headers headers)] (.addHeader request h))
      (when-not (nil? payload)
        (.setPayload request payload))
      request)))

(defn fetch
  "Fetch a URL using AppEngine's URLFetch service.

  url can be either a string or a java.net.URL object.

  Optional parameters:
    :method           :get (the default), :delete, :head, :post or :put.
    :headers          A map from :name to string.
    :payload          Java byte array
    :allow-truncate   If true, allow appengine to truncate a big response
                      without error. If false, throw an exception instead.
    :follow-redirects Self-explanatory boolean.
    :deadline         Deadline for the request, in seconds.
    :async?           Perform the request asynchronously and return future-
                      like object.

  Note that :allow-truncate, :follow-redirects and :deadline use the
  AppEngine defaults, whatever they are."
  [url & {:keys [async?] :or {async? false} :as opts}]
  (let [opts (flatten (vec opts))]
    (if async?
      (log/warn "async fetch not yet implemented")
      ;; (derefify-future (.fetchAsync (get-urlfetch-service)
      ;;                               (apply make-request (urlify url) opts))
      ;;                  :deref-fn #(parse-response (.get %)))
      (parse-response
       (try
         (.fetch (get-urlfetch-service)
                 (apply make-request (urlify url) opts))
         (catch java.io.IOException e
           ;; remote service could not be contacted or the URL could not be fetched.
           (log/warn "migae.migae-urlfetch fetch IOException: " (.getMessage e))
           (throw e))
         (catch com.google.appengine.api.urlfetch.ResponseTooLargeException e
           (log/warn "migae.migae-urlfetch fetch GAE ResponseTooLargeException: "
                (.getMessage e))
           (throw e))
         (catch java.net.MalformedURLException e
           (log/warn "migae.migae-urlfetch fetch: MalformedURLException: " (.getMessage e))
                    (throw e))
         (catch java.net.SocketTimeoutException e
           (log/warn "migae.migae-urlfetch fetch SocketTimeoutException: " (.getMessage e))
                    (throw e))
         (catch javax.net.ssl.SSLHandshakeException e
           ;; server's SSL cert could not be validated and validation was requested.
           (log/warn "migae.migae-urlfetch fetch SSLHandshakeException: " (.getMessage e))
           (throw e))
         (catch Exception e
           ;; server's SSL cert could not be validated and validation was requested.
           (log/warn "migae.migae-urlfetch fetch Exception: " (.getMessage e))
                    (throw e))
         )))))


