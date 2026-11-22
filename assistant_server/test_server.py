import json
import os
import unittest
from unittest.mock import patch

import httpx

from assistant_server import server


class AssistantApiTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        environment = patch.dict(
            os.environ,
            {"ASSISTANT_ACCESS_CODE": "local-test", "DEEPSEEK_API_KEY": "fixture-only"},
        )
        environment.start()
        self.addCleanup(environment.stop)
        self.client = httpx.AsyncClient(
            transport=httpx.ASGITransport(app=server.app),
            base_url="http://test",
            headers={"Authorization": "Bearer local-test"},
        )
        self.addAsyncCleanup(self.client.aclose)

    async def test_health_checks_access_code_and_reports_missing_model_key(self):
        self.assertEqual((await self.client.get("/health")).json(), {"status": "ok", "model_configured": True})
        reply = await self.client.get("/health", headers={"Authorization": "Bearer wrong"})
        self.assertEqual(reply.status_code, 401)
        with patch.dict(os.environ, {"DEEPSEEK_API_KEY": ""}):
            self.assertFalse((await self.client.get("/health")).json()["model_configured"])
            self.assertEqual((await self.client.post("/assistant", json={"text": "你好"})).status_code, 503)

    async def submit_with_result(self, result, status="completed", home_state=None):
        response = {
            "status": status,
            "output": [{
                "type": "message",
                "content": [{"type": "output_text", "text": json.dumps(result, ensure_ascii=False)}],
            }],
        }
        with patch.object(server, "fetch_response", return_value=response) as upstream:
            reply = await self.client.post(
                "/assistant",
                json={
                    "text": "测试",
                    "tracks": [{"id": "track_1", "name": "演员"}],
                    "channels": [{"id": "cctv13", "name": "CCTV-13 新闻"}],
                    "home_state": home_state or {},
                },
            )
        upstream.assert_awaited_once()
        payload, _ = upstream.await_args.args
        self.assertEqual(payload["model"], "deepseek-flash")
        self.assertEqual(payload["reasoning"], {"effort": "none"})
        self.assertEqual(payload["text"]["format"]["type"], "json_schema")
        self.assertEqual(
            set(payload["text"]["format"]["schema"]["required"]),
            {"kind", "target_id", "message", "home_options"},
        )
        if home_state:
            forwarded_home = json.loads(payload["input"])["home_state"]
            self.assertEqual(forwarded_home["air_conditioner"], home_state["air_conditioner"])
        return reply

    async def test_returns_valid_answers_actions_and_clarification(self):
        for kind, target_id, message in (
            ("answer", None, "这是一个回答。"),
            ("clarify", None, "请问想看哪个频道？"),
            ("play_music", "track_1", None),
            ("play_tv", "cctv13", None),
        ):
            with self.subTest(kind=kind):
                result = dict(kind=kind, target_id=target_id, message=message, home_options=[])
                reply = await self.submit_with_result(result)
                self.assertEqual(reply.status_code, 200)
                self.assertEqual(reply.json(), result)

    async def test_rejects_invalid_actions_and_incomplete_model_results(self):
        for kind, target_id, home_options in (
            ("play_tv", "track_1", []),
            ("play_music", "unknown", []),
            ("confirm_home", None, [self.home_option("curtain", power="off")]),
            ("confirm_home", None, [self.home_option("air_conditioner", temperature_c="99")]),
            ("confirm_home", None, [self.home_option("unregistered_device", power="on")]),
        ):
            with self.subTest(kind=kind, target_id=target_id):
                reply = await self.submit_with_result(
                    dict(kind=kind, target_id=target_id, message=None, home_options=home_options)
                )
                self.assertEqual(reply.status_code, 502)
        reply = await self.submit_with_result(
            dict(kind="answer", target_id=None, message="部分内容", home_options=[]),
            status="incomplete",
        )
        self.assertEqual(reply.status_code, 502)

    @staticmethod
    def home_option(device_id, **settings):
        return {
            "device_id": device_id,
            "settings": [{"name": name, "value": value} for name, value in settings.items()],
        }

    async def test_model_receives_home_state_and_returns_validated_options(self):
        home_state = {
            "air_conditioner": {
                "power_on": True, "temperature_c": 23, "mode": "cool",
                "swing": "down", "off_timer_minutes": 30,
            }
        }
        proposed = self.home_option("air_conditioner", swing="up", off_timer_minutes="60")
        response = await self.submit_with_result(
            dict(kind="confirm_home", target_id=None, message=None, home_options=[proposed, proposed]),
            home_state=home_state,
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["home_options"], [proposed])

    async def test_exact_home_suggestions_use_current_state(self):
        inputs = (
            ("我热", {}, [self.home_option("air_conditioner", temperature_c="25")]),
            ("我热！", {"air_conditioner": {"power_on": False, "mode": "heat"}},
             [self.home_option("air_conditioner", power="on", mode="cool")]),
            ("我热", {"air_conditioner": {"power_on": True, "mode": "fan"}},
             [self.home_option("air_conditioner", mode="cool")]),
            ("开灯", {}, [
                self.home_option("main_light", power="on"),
                self.home_option("bedside_light", power="on"),
            ]),
            ("开灯", {"main_light_on": True}, [self.home_option("bedside_light", power="on")]),
        )
        with patch.object(server, "fetch_response") as upstream:
            for prompt, home_state, options in inputs:
                with self.subTest(prompt=prompt, home_state=home_state):
                    response = await self.client.post("/assistant", json={"text": prompt, "home_state": home_state})
                    self.assertEqual(response.status_code, 200)
                    self.assertEqual(response.json()["kind"], "confirm_home")
                    self.assertEqual(response.json()["home_options"], options)
            upstream.assert_not_awaited()
