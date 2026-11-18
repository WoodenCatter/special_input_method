from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


DEVICES = {
    "main_light": {"name": "大灯（客厅主灯）", "settings": {"power": ["on", "off"]}},
    "bedside_light": {"name": "床头灯", "settings": {"power": ["on", "off"]}},
    "curtain": {"name": "窗帘", "settings": {"position_percent": "0–100，0 为关，100 为全开"}},
    "air_conditioner": {
        "name": "空调",
        "settings": {
            "power": ["on", "off"],
            "temperature_c": "16–30，整数摄氏度",
            "mode": ["cool", "heat", "auto", "dry", "fan"],
            "swing": ["auto", "up", "down", "fixed"],
            "off_timer_minutes": "0–720，倒计时关机分钟数，0 取消定时",
        },
    },
}


class AirConditionerState(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    power_on: bool = True
    temperature_c: int = Field(default=26, ge=16, le=30)
    mode: Literal["cool", "heat", "auto", "dry", "fan"] = "cool"
    swing: Literal["auto", "up", "down", "fixed"] = "auto"
    off_timer_minutes: int = Field(default=0, ge=0, le=720)


class HomeState(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    main_light_on: bool = False
    bedside_light_on: bool = False
    curtain_position_percent: int = Field(default=0, ge=0, le=100)
    air_conditioner: AirConditionerState = Field(default_factory=AirConditionerState)


class HomeSetting(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    name: Literal["power", "position_percent", "temperature_c", "mode", "swing", "off_timer_minutes"]
    value: str


def in_range(value: str, minimum: int, maximum: int) -> bool:
    return (
        1 <= len(value) <= 3
        and value.isascii()
        and value.isdecimal()
        and str(int(value)) == value
        and minimum <= int(value) <= maximum
    )


class HomeAction(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    device_id: Literal["main_light", "bedside_light", "curtain", "air_conditioner"]
    settings: list[HomeSetting] = Field(min_length=1, max_length=5)

    @model_validator(mode="after")
    def validate_settings(self):
        settings = DEVICES[self.device_id]["settings"]
        seen = set()
        bounds = {"position_percent": (0, 100), "temperature_c": (16, 30), "off_timer_minutes": (0, 720)}
        for item in self.settings:
            if item.name in seen or item.name not in settings:
                raise ValueError("Unexpected or repeated device setting")
            seen.add(item.name)
            choices = settings[item.name]
            valid = item.value in choices if isinstance(choices, list) else in_range(item.value, *bounds[item.name])
            if not valid:
                raise ValueError("Invalid device setting value")
        return self

    def validate_against(self, state: HomeState):
        if self.device_id != "air_conditioner":
            return
        settings = {item.name: item.value for item in self.settings}
        ends_powered_on = settings.get("power", "on" if state.air_conditioner.power_on else "off") == "on"
        if not ends_powered_on and settings.get("off_timer_minutes", "0") != "0":
            raise ValueError("Turn on the air conditioner before scheduling shutdown")


def common_home_suggestion(text: str, state: HomeState) -> list[HomeAction] | str | None:
    phrase = text.strip().rstrip("。！!").strip()
    if phrase in {"开灯", "关灯"}:
        turn_on = phrase == "开灯"
        setting = HomeSetting(name="power", value="on" if turn_on else "off")
        options = []
        if state.main_light_on != turn_on:
            options.append(HomeAction(device_id="main_light", settings=[setting]))
        if state.bedside_light_on != turn_on:
            options.append(HomeAction(device_id="bedside_light", settings=[setting]))
        return options or ("两盏灯已经打开。" if turn_on else "两盏灯已经关闭。")

    if phrase not in {"我热", "我好热", "太热了", "我有点热", "我觉得热"}:
        return None

    ac = state.air_conditioner
    changes = []
    if not ac.power_on:
        changes.append(HomeSetting(name="power", value="on"))
        if ac.mode != "cool":
            changes.append(HomeSetting(name="mode", value="cool"))
    elif ac.mode != "cool":
        changes.append(HomeSetting(name="mode", value="cool"))
    elif ac.temperature_c > 16:
        changes.append(HomeSetting(name="temperature_c", value=str(ac.temperature_c - 1)))
    else:
        return "空调已经在制冷，设定温度已是最低的 16°C。"
    return [HomeAction(device_id="air_conditioner", settings=changes)]
