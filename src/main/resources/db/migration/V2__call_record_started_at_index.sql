-- 统计页（跨网关按时间窗聚合）需要的索引。
--
-- V1 里已有 ix_tool_call_record_gateway_started (gateway_id, started_at)，但它以
-- gateway_id 打头：统计页的"全部网关"视图只按 started_at 筛，用不上那个索引，
-- 会退化成整表扫描。调用记录是这套系统里增长最快的表，长期运行下这个差别很明显。
CREATE INDEX ix_tool_call_record_started ON tool_call_record (started_at);
