-- 子 MCP 说明（与 gateway_tool 的同名两列同构）：
-- original_description：同步时从下游 MCP initialize 结果的 instructions 自动捕获的原文，
--                       协议字段，仅同步成功时写入，失败保留上一次的值（需求 6.4.7 同款语义）。
-- custom_description：操作人写的覆盖描述，非空时完全替换原始描述；
--                      长度限制 ≤ 4000 字符在应用层校验（同 UpdateToolRequest 的做法）。
ALTER TABLE downstream_mcp ADD COLUMN original_description CLOB;
ALTER TABLE downstream_mcp ADD COLUMN custom_description CLOB;
