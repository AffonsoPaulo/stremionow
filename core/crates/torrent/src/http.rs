// SPDX-License-Identifier: GPL-3.0-or-later
//! The loopback HTTP/1.1 listener and helpers shared by the routes.

use std::convert::Infallible;
use std::future::Future;
use std::io;
use std::sync::Arc;
use std::time::Duration;

use bytes::Bytes;
use futures::{Stream, TryStreamExt};
use http_body_util::combinators::UnsyncBoxBody;
use http_body_util::{BodyExt, Empty, StreamBody};
use hyper::body::{Frame, Incoming};
use hyper::header::{self, HeaderValue};
use hyper::server::conn::http1;
use hyper::service::service_fn;
use hyper::{Request, Response, StatusCode};
use hyper_util::rt::{TokioIo, TokioTimer};
use tokio::net::TcpListener;
use tokio::sync::Semaphore;
use tokio_util::sync::CancellationToken;

/// Response body: streamed, never collected in memory.
pub(crate) type Body = UnsyncBoxBody<Bytes, io::Error>;

/// Connections served at once. Players open one or two; the limit bounds what a misbehaving
/// local client can make the server hold.
const MAX_CONNECTIONS: usize = 64;

/// Time a client gets to send request headers before its connection is closed.
const HEADER_TIMEOUT: Duration = Duration::from_secs(30);

/// Accepts connections on `listener` until `shutdown` is cancelled, answering each request
/// with `handle`.
pub(crate) async fn serve<H, F>(listener: TcpListener, shutdown: CancellationToken, handle: H)
where
    H: Fn(Request<Incoming>) -> F + Clone + Send + Sync + 'static,
    F: Future<Output = Response<Body>> + Send + 'static,
{
    let slots = Arc::new(Semaphore::new(MAX_CONNECTIONS));
    loop {
        let Ok(slot) = slots.clone().acquire_owned().await else {
            return;
        };
        let accepted = tokio::select! {
            () = shutdown.cancelled() => return,
            accepted = listener.accept() => accepted,
        };
        let stream = match accepted {
            Ok((stream, _)) => stream,
            Err(error) => {
                // Usually a transient lack of file descriptors; back off instead of spinning.
                log::warn!("stream server: accept failed: {error}");
                tokio::time::sleep(Duration::from_millis(100)).await;
                continue;
            }
        };
        let handle = handle.clone();
        let shutdown = shutdown.clone();
        tokio::spawn(async move {
            let service = service_fn(move |request| {
                let response = handle(request);
                async move { Ok::<_, Infallible>(response.await) }
            });
            let connection = http1::Builder::new()
                .timer(TokioTimer::new())
                .header_read_timeout(HEADER_TIMEOUT)
                .serve_connection(TokioIo::new(stream), service);
            tokio::select! {
                () = shutdown.cancelled() => {}
                result = connection => {
                    if let Err(error) = result {
                        log::debug!("stream server: connection ended: {error}");
                    }
                }
            }
            drop(slot);
        });
    }
}

/// A response with no body.
pub(crate) fn empty(status: StatusCode) -> Response<Body> {
    let mut response = Response::new(Empty::new().map_err(|never| match never {}).boxed_unsync());
    *response.status_mut() = status;
    response
}

/// `404 Not Found`, the answer to anything not addressed with the right secret and token.
pub(crate) fn not_found() -> Response<Body> {
    empty(StatusCode::NOT_FOUND)
}

/// `405 Method Not Allowed` for anything but GET and HEAD.
pub(crate) fn method_not_allowed() -> Response<Body> {
    let mut response = empty(StatusCode::METHOD_NOT_ALLOWED);
    response
        .headers_mut()
        .insert(header::ALLOW, HeaderValue::from_static("GET, HEAD"));
    response
}

/// A body streaming the chunks of `stream`.
pub(crate) fn stream_body<S>(stream: S) -> Body
where
    S: Stream<Item = io::Result<Bytes>> + Send + 'static,
{
    StreamBody::new(stream.map_ok(Frame::data)).boxed_unsync()
}
