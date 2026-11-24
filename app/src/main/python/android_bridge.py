"""Run the Flowseal core in a worker managed by the Android service."""
import asyncio
import json
import logging

from proxy.config import (
    coerce_domain_list, parse_dc_ip_list, proxy_config, stop_cfproxy_domain_refresh,
)
from proxy.stats import stats
from proxy import tg_ws_proxy


class AndroidLog(logging.Handler):
    def __init__(self, sink, secret):
        super().__init__()
        self.sink = sink
        self.secret = secret
        self.setFormatter(logging.Formatter("%(asctime)s  %(levelname)s  %(message)s", "%H:%M:%S"))

    def emit(self, record):
        message = self.format(record)
        if self.secret in message:
            return
        self.sink.log(message)


async def serve(sink):
    stop = asyncio.Event()
    task = asyncio.create_task(tg_ws_proxy._run(stop, use_free_port=True))
    ready = False
    try:
        while not task.done():
            if sink.stopping():
                stop.set()
                break
            server = tg_ws_proxy._server_instance
            if server is not None and server.is_serving():
                if not ready:
                    sink.ready(server.sockets[0].getsockname()[1])
                    ready = True
                sink.metrics(stats.connections_active, stats.connections_total,
                             stats.bytes_up, stats.bytes_down)
            await asyncio.wait((task,), timeout=0.25)
        await task
    finally:
        stop.set()
        if not task.done():
            await task


def run(payload, sink):
    settings = json.loads(payload)
    proxy_config.host = "127.0.0.1"
    proxy_config.port = settings["port"]
    proxy_config.secret = settings["secret"]
    proxy_config.pool_size = settings["pool"]
    proxy_config.dc_redirects = parse_dc_ip_list(coerce_domain_list(settings["dc"]))
    proxy_config.fallback_cfproxy = settings["cloudflare"]
    proxy_config.cfproxy_user_domains = coerce_domain_list(settings["domains"])
    proxy_config.cfproxy_worker_domains = coerce_domain_list(settings["workers"])
    proxy_config.cfproxy_h2_media = settings["h2"]
    proxy_config.allow_insecure_cf_fallback = settings.get("httpFallback", False)
    proxy_config.force_test_dc = settings["testDc"]
    stats.__init__()

    root = logging.getLogger()
    old_level = root.level
    handler = AndroidLog(sink, proxy_config.secret)
    root.addHandler(handler)
    root.setLevel(logging.DEBUG if settings["debug"] else logging.INFO)
    try:
        if not sink.stopping():
            asyncio.run(serve(sink))
    finally:
        stop_cfproxy_domain_refresh()
        root.removeHandler(handler)
        root.setLevel(old_level)
