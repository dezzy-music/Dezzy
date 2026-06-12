import asyncio
import websockets
import json

async def test_ws():
    uri = "wss://dezzyyyy-listen-together.hf.space/ws"
    async with websockets.connect(uri) as websocket:
        msg = json.dumps({"type": "create_room", "payload": {"username": "Test"}})
        print(f"> Sending Text: {msg}")
        await websocket.send(msg)
        
        response = await websocket.recv()
        print(f"< Received Text: {response!r}")
        
        # Test binary
        binary_msg = msg.encode('utf-8')
        print(f"> Sending Binary: {binary_msg!r}")
        await websocket.send(binary_msg)
        
        response2 = await websocket.recv()
        print(f"< Received after binary: {response2!r}")

asyncio.run(test_ws())
