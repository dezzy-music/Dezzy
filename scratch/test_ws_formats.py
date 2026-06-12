import asyncio
import websockets
import json

async def test_ws():
    uri = "wss://dezzyyyy-listen-together.hf.space/ws"
    
    formats_to_test = [
        {"type": "create_room", "payload": {"username": "Test"}},
        {"type": "create_room", "username": "Test"},
        ["create_room", {"username": "Test"}],
        "42[\"create_room\", {\"username\": \"Test\"}]",
        "create_room:{\"username\":\"Test\"}"
    ]
    
    async with websockets.connect(uri) as websocket:
        for fmt in formats_to_test:
            msg = json.dumps(fmt) if not isinstance(fmt, str) else fmt
            print(f"\n> Sending: {msg}")
            await websocket.send(msg)
            try:
                response = await asyncio.wait_for(websocket.recv(), timeout=2.0)
                print(f"< Received: {response!r}")
            except Exception as e:
                print(f"Error or timeout: {e}")

asyncio.run(test_ws())
