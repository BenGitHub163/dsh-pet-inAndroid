"""聊天状态 → 桌宠的轻量桥（一个 11 行的适配器）。

聊天窗在 `thinking / streaming / success / error` 时调用这里，本类再把状态转发给
`PetWindow.set_chat_status()`，让桌宠跟着换表情/动一下、或者在气泡里显示聊天状态。

之所以单独一个文件：聊天子系统不该知道桌宠窗口的存在，只认这个接口。
"""

from __future__ import annotations
class PetChatLink:
    def __init__(self,pet_window=None): self.pet_window=pet_window; self.on_status=None
    def set_window(self,w): self.pet_window=w
    def _notify(self,state,text=''):
        if self.on_status: self.on_status(state,text)
        if self.pet_window and hasattr(self.pet_window,'set_chat_status'): self.pet_window.set_chat_status(state,text)
    def thinking(self,text='正在想怎么回答…'): self._notify('thinking',text)
    def streaming(self,text): self._notify('streaming',text[:60])
    def success(self): self._notify('success','')
    def error(self,text): self._notify('error',text[:80])