"""Linux BlueZ GATT peripheral. Run on the Pi, not on macOS."""

import argparse
import asyncio
import logging

from dbus_next import BusType, Variant
from dbus_next.aio import MessageBus
from dbus_next.constants import PropertyAccess
from dbus_next.errors import DBusError
from dbus_next.service import ServiceInterface, dbus_property, method

from .protocol import CONTROL_UUID, DATA_UUID, SERVICE_UUID
from .transfer import TransferSender

ROOT = "/com/roadgateway"
SERVICE = ROOT + "/service0"
ADVERT = "/com/roadadvert"
READ = PropertyAccess.READ


class GattService(ServiceInterface):
    def __init__(self):
        super().__init__("org.bluez.GattService1")

    @dbus_property(access=READ)
    def UUID(self) -> "s":
        return SERVICE_UUID

    @dbus_property(access=READ)
    def Primary(self) -> "b":
        return True

    @dbus_property(access=READ)
    def Includes(self) -> "ao":
        return []


class Characteristic(ServiceInterface):
    def __init__(self, uuid, flags):
        super().__init__("org.bluez.GattCharacteristic1")
        self.uuid = uuid
        self.flags = flags

    @dbus_property(access=READ)
    def UUID(self) -> "s":
        return self.uuid

    @dbus_property(access=READ)
    def Service(self) -> "o":
        return SERVICE

    @dbus_property(access=READ)
    def Flags(self) -> "as":
        return self.flags


class DataCharacteristic(Characteristic):
    def __init__(self):
        super().__init__(DATA_UUID, ["notify"])
        self.value = b""
        self.sender = None

    @dbus_property(access=READ)
    def Value(self) -> "ay":
        return self.value

    @dbus_property(access=READ)
    def Notifying(self) -> "b":
        return bool(self.sender and self.sender.notifying)

    @method()
    def StartNotify(self):
        self.sender.notifying = True
        self.emit_properties_changed({"Notifying": True})

    @method()
    def StopNotify(self):
        self.sender.notifying = False
        self.sender.stop()
        self.emit_properties_changed({"Notifying": False})

    def send(self, packet):
        if self.sender.notifying:
            self.value = packet
            self.emit_properties_changed({"Value": packet})


class ControlCharacteristic(Characteristic):
    def __init__(self, sender):
        super().__init__(CONTROL_UUID, ["write"])
        self.sender = sender

    @method()
    def WriteValue(self, value: "ay", options: "a{sv}"):
        if options.get("offset", Variant("q", 0)).value != 0:
            raise DBusError("org.bluez.Error.InvalidOffset", "Offset must be zero")
        mtu = options.get("mtu", Variant("q", 23)).value
        self.sender.control(bytes(value), mtu)


class ObjectManager(ServiceInterface):
    def __init__(self, objects):
        super().__init__("org.freedesktop.DBus.ObjectManager")
        self.objects = objects

    @method()
    def GetManagedObjects(self) -> "a{oa{sa{sv}}}":
        result = {}
        for path, interface in self.objects.items():
            props = {"UUID": Variant("s", interface.UUID)}
            if isinstance(interface, GattService):
                props.update(Primary=Variant("b", True), Includes=Variant("ao", []))
            else:
                props.update(Service=Variant("o", SERVICE), Flags=Variant("as", interface.Flags))
            result[path] = {interface.name: props}
        return result


class Advertisement(ServiceInterface):
    def __init__(self):
        super().__init__("org.bluez.LEAdvertisement1")

    @dbus_property(access=READ)
    def Type(self) -> "s":
        return "peripheral"

    @dbus_property(access=READ)
    def ServiceUUIDs(self) -> "as":
        return [SERVICE_UUID]

    @dbus_property(access=READ)
    def LocalName(self) -> "s":
        return "RoadPi"

    @method()
    def Release(self):
        logging.info("Advertisement released")


async def serve(spool, adapter):
    bus = await MessageBus(bus_type=BusType.SYSTEM).connect()
    adapter_path = f"/org/bluez/{adapter}"
    proxy = bus.get_proxy_object("org.bluez", adapter_path, await bus.introspect("org.bluez", adapter_path))
    props = proxy.get_interface("org.freedesktop.DBus.Properties")
    await props.call_set("org.bluez.Adapter1", "Powered", Variant("b", True))
    manager = proxy.get_interface("org.bluez.GattManager1")
    advertising = proxy.get_interface("org.bluez.LEAdvertisingManager1")
    data = DataCharacteristic()
    sender = TransferSender(spool, data.send)
    data.sender = sender
    objects = {SERVICE: GattService(), SERVICE + "/data": data,
               SERVICE + "/control": ControlCharacteristic(sender)}
    bus.export(ROOT, ObjectManager(objects))
    for path, interface in objects.items():
        bus.export(path, interface)
    bus.export(ADVERT, Advertisement())
    registered_app = registered_ad = False
    try:
        await manager.call_register_application(ROOT, {})
        registered_app = True
        await advertising.call_register_advertisement(ADVERT, {})
        registered_ad = True
        logging.info("RoadPi ready on %s; queue=%s", adapter, spool)
        await asyncio.Future()
    finally:
        sender.stop()
        if registered_ad:
            await advertising.call_unregister_advertisement(ADVERT)
        if registered_app:
            await manager.call_unregister_application(ROOT)
        bus.disconnect()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--spool", default="spool")
    parser.add_argument("--adapter", default="hci0")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    try:
        asyncio.run(serve(args.spool, args.adapter))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
