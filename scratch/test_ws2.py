import asyncio
import websockets
import json

async def test_ws():
    uri = "wss://dezzyyyy-listen-together.hf.space/ws?format=json"
    try:
        async with websockets.connect(uri) as websocket:
            msg = json.dumps({"type": "create_room", "payload": {"username": "Test"}})
            print(f"> Sending Text: {msg}")
            await websocket.send(msg)
            
            response = await websocket.recv()
            print(f"< Received Text: {response!r}")
    except Exception as e:
        print(f"Error: {e}")

asyncio.run(test_ws())
