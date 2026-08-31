'use strict';

/*
 * 管理前端的全部脚本（需求 14：少量原生 JavaScript，不引入前端构建链）。
 *
 * 页面由 Thymeleaf 服务端渲染，这里只负责三件事：发请求、显示反馈、刷新页面。
 * 渲染逻辑不在这里重复一遍 —— 视图模型只有服务端那一份。
 */

(function () {

  // ---------------------------------------------------------------- 反馈

  function alertsContainer() {
    return document.getElementById('alerts');
  }

  function showAlert(kind, title, detail) {
    const container = alertsContainer();
    if (!container) {
      return;
    }
    const box = document.createElement('div');
    box.className = 'alert alert-' + kind + ' alert-dismissible';
    const strong = document.createElement('strong');
    strong.textContent = title;
    box.appendChild(strong);
    if (detail) {
      // 一律用 textContent 而不是 innerHTML：错误文案里可能带下游返回的内容，
      // 拼进 HTML 就是一个存储型 XSS。
      const p = document.createElement('div');
      p.className = 'small mt-1';
      p.textContent = detail;
      box.appendChild(p);
    }
    const close = document.createElement('button');
    close.type = 'button';
    close.className = 'btn-close';
    close.addEventListener('click', function () { box.remove(); });
    box.appendChild(close);
    container.prepend(box);
    box.scrollIntoView({ block: 'nearest' });
  }

  function showError(title, detail) {
    showAlert('danger', title, detail);
  }

  function showSuccess(title, detail) {
    showAlert('success', title, detail);
  }

  // ---------------------------------------------------------------- 请求

  /**
   * 统一处理 {success, data, error} 响应结构。
   * 失败时抛出带稳定错误码的异常，由各个调用点决定怎么展示。
   */
  async function api(method, url, body) {
    const options = { method: method, headers: { 'Accept': 'application/json' } };
    if (body !== undefined) {
      options.headers['Content-Type'] = 'application/json';
      options.body = JSON.stringify(body);
    }
    const response = await fetch(url, options);
    let payload = null;
    try {
      payload = await response.json();
    } catch (e) {
      throw new Error('服务端返回了无法解析的响应（HTTP ' + response.status + '）');
    }
    if (!response.ok || !payload || payload.success !== true) {
      const error = (payload && payload.error) || {};
      const message = error.code ? (error.code + '：' + (error.message || '')) : ('HTTP ' + response.status);
      throw new Error(message);
    }
    return payload.data;
  }

  /** 提交期间禁用按钮，避免重复点击造成重复导入或重复轮换。 */
  async function withBusy(element, action) {
    if (!element) {
      return action();
    }
    const original = element.textContent;
    element.disabled = true;
    element.textContent = '处理中…';
    try {
      return await action();
    } finally {
      element.disabled = false;
      element.textContent = original;
    }
  }

  function gatewayId() {
    return document.body.dataset.gatewayId;
  }

  function reloadSoon() {
    window.setTimeout(function () { window.location.reload(); }, 600);
  }

  // ---------------------------------------------------------------- 令牌

  /** 需求 FR-05.3：令牌只完整显示一次，展示后不再从服务端取得。 */
  function showToken(token) {
    const panel = document.getElementById('token-panel');
    const field = document.getElementById('token-value');
    if (!panel || !field) {
      return;
    }
    field.value = token;
    panel.hidden = false;
    panel.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }

  async function copyFrom(elementId, button) {
    const source = document.getElementById(elementId);
    if (!source) {
      return;
    }
    try {
      await navigator.clipboard.writeText(source.value !== undefined ? source.value : source.textContent);
      const original = button.textContent;
      button.textContent = '已复制';
      window.setTimeout(function () { button.textContent = original; }, 1500);
    } catch (e) {
      // 非 HTTPS 或浏览器不允许时退回手工复制。
      source.focus();
      source.select();
      showError('无法自动复制', '请手动复制已选中的内容。');
    }
  }

  // ---------------------------------------------------------------- 列表页

  function bindCreateGateway() {
    const form = document.getElementById('create-form');
    if (!form) {
      return;
    }
    form.addEventListener('submit', async function (event) {
      event.preventDefault();
      const button = form.querySelector('button[type="submit"]');
      const description = form.elements.description.value.trim();
      await withBusy(button, async function () {
        try {
          const created = await api('POST', '/api/gateways', {
            name: form.elements.name.value.trim(),
            slug: form.elements.slug.value.trim(),
            description: description === '' ? null : description
          });
          showToken(created.accessToken);
          showSuccess('网关已创建', '令牌已显示在下方，只显示这一次。');
          form.reset();
          document.getElementById('create-panel').hidden = true;
          // 不立刻刷新：刷新会把令牌冲掉。等操作人复制完自己点进详情。
        } catch (e) {
          showError('创建网关失败', e.message);
        }
      });
    });
  }

  async function deleteGateway(button) {
    const id = button.dataset.gatewayId;
    const name = button.dataset.gatewayName;
    // 需求 6.1.7：删除必须二次确认，并说清连带影响。
    if (!window.confirm('确定删除网关「' + name + '」？\n\n'
        + '它的子 MCP 配置、工具快照和全部调用记录都会一并删除，且无法恢复。')) {
      return;
    }
    await withBusy(button, async function () {
      try {
        await api('DELETE', '/api/gateways/' + encodeURIComponent(id));
        showSuccess('网关已删除', '');
        window.location.href = '/ui/gateways';
      } catch (e) {
        showError('删除网关失败', e.message);
      }
    });
  }

  // ---------------------------------------------------------------- 详情页

  function bindGatewayForm() {
    const form = document.getElementById('gateway-form');
    if (!form) {
      return;
    }
    form.addEventListener('submit', async function (event) {
      event.preventDefault();
      const button = form.querySelector('button[type="submit"]');
      const currentSlug = form.elements.slug.defaultValue;
      const newSlug = form.elements.slug.value.trim();
      if (newSlug !== currentSlug && !window.confirm(
          'slug 从「' + currentSlug + '」改为「' + newSlug + '」会改变 Agent 的 MCP 地址，\n'
          + '已接入的 Agent 需要更新配置。确定继续？')) {
        return;
      }
      const description = form.elements.description.value.trim();
      await withBusy(button, async function () {
        try {
          await api('PUT', '/api/gateways/' + encodeURIComponent(gatewayId()), {
            name: form.elements.name.value.trim(),
            slug: newSlug,
            description: description === '' ? null : description
          });
          showSuccess('网关已保存', '');
          reloadSoon();
        } catch (e) {
          showError('保存网关失败', e.message);
        }
      });
    });
  }

  function describeSyncResults(results) {
    if (!results || results.length === 0) {
      return '';
    }
    return results.map(function (r) {
      if (r.succeeded) {
        return r.downstreamName + '：新增 ' + r.added + '，更新 ' + r.updated
            + '，未变 ' + r.unchanged + '，移除 ' + r.removed;
      }
      return r.downstreamName + '：同步失败（' + r.errorCode + '）' + (r.errorMessage || '');
    }).join('\n');
  }

  function bindImportForm() {
    const form = document.getElementById('import-form');
    if (!form) {
      return;
    }
    form.addEventListener('submit', async function (event) {
      event.preventDefault();
      const button = form.querySelector('button[type="submit"]');
      const raw = document.getElementById('import-json').value.trim();
      let parsed;
      try {
        parsed = JSON.parse(raw);
      } catch (e) {
        showError('配置 JSON 格式错误', e.message);
        return;
      }
      await withBusy(button, async function () {
        try {
          const result = await api('POST',
              '/api/gateways/' + encodeURIComponent(gatewayId()) + '/mcp-servers/import', parsed);
          const failed = (result.syncResults || []).filter(function (r) { return !r.succeeded; });
          // 配置一定全部落库，同步逐个成败 —— 这两件事要分开说清楚。
          if (failed.length > 0) {
            showAlert('warning', '子 MCP 已导入，但有同步失败', describeSyncResults(result.syncResults));
          } else {
            showSuccess('子 MCP 已导入并同步', describeSyncResults(result.syncResults));
          }
          reloadSoon();
        } catch (e) {
          showError('导入子 MCP 失败', e.message);
        }
      });
    });
  }

  async function syncDownstream(button) {
    const id = button.dataset.downstreamId;
    await withBusy(button, async function () {
      try {
        const result = await api('POST', '/api/gateways/' + encodeURIComponent(gatewayId())
            + '/mcp-servers/' + encodeURIComponent(id) + '/sync');
        if (result.succeeded) {
          showSuccess('同步成功', describeSyncResults([result]));
        } else {
          // 同步失败不是请求失败：上一次成功的快照仍然保留。
          showAlert('warning', '同步失败，已保留上一次成功的工具快照',
              result.errorCode + '：' + (result.errorMessage || ''));
        }
        reloadSoon();
      } catch (e) {
        showError('同步失败', e.message);
      }
    });
  }

  function bindDownstreamForms() {
    document.querySelectorAll('.downstream-form').forEach(function (form) {
      const replaceCheckbox = form.elements.replaceHeaders;
      const headersField = form.elements.headers;
      if (replaceCheckbox && headersField) {
        replaceCheckbox.addEventListener('change', function () {
          headersField.hidden = !replaceCheckbox.checked;
        });
      }

      form.addEventListener('submit', async function (event) {
        event.preventDefault();
        const button = form.querySelector('button[type="submit"]');
        const body = {
          name: form.elements.name.value.trim(),
          url: form.elements.url.value.trim()
        };
        // 不勾"替换 headers"就完全不传这个字段 —— 传遮罩值会把真凭证覆盖掉。
        if (replaceCheckbox && replaceCheckbox.checked) {
          const raw = headersField.value.trim();
          try {
            body.headers = raw === '' ? {} : JSON.parse(raw);
          } catch (e) {
            showError('headers 不是合法 JSON', e.message);
            return;
          }
        }
        const currentName = form.elements.name.defaultValue;
        if (body.name !== currentName && !window.confirm(
            '子 MCP 从「' + currentName + '」改名为「' + body.name + '」会改变它下面所有工具的聚合名，\n'
            + '已接入的 Agent 需要重新拉取工具列表。确定继续？')) {
          return;
        }
        await withBusy(button, async function () {
          try {
            await api('PUT', '/api/gateways/' + encodeURIComponent(gatewayId())
                + '/mcp-servers/' + encodeURIComponent(form.dataset.downstreamId), body);
            showSuccess('子 MCP 已保存', '');
            reloadSoon();
          } catch (e) {
            showError('保存子 MCP 失败', e.message);
          }
        });
      });
    });
  }

  async function deleteDownstream(button) {
    const id = button.dataset.downstreamId;
    const name = button.dataset.downstreamName;
    if (!window.confirm('确定删除子 MCP「' + name + '」？\n\n'
        + '它的工具会立即从总 MCP 的工具列表中消失，且不可再调用。')) {
      return;
    }
    await withBusy(button, async function () {
      try {
        await api('DELETE', '/api/gateways/' + encodeURIComponent(gatewayId())
            + '/mcp-servers/' + encodeURIComponent(id));
        showSuccess('子 MCP 已删除', '');
        reloadSoon();
      } catch (e) {
        showError('删除子 MCP 失败', e.message);
      }
    });
  }

  async function toggleTool(input) {
    const id = input.dataset.toolId;
    const enabled = input.checked;
    input.disabled = true;
    try {
      await api('PATCH', '/api/gateways/' + encodeURIComponent(gatewayId())
          + '/tools/' + encodeURIComponent(id), { enabled: enabled });
      showSuccess(enabled ? '工具已启用' : '工具已停用',
          enabled ? '立即可被发现和调用。' : '已从 tools/list 移除，调用请求不会转发到下游。');
    } catch (e) {
      input.checked = !enabled;
      showError('修改启用状态失败', e.message);
    } finally {
      input.disabled = false;
    }
  }

  async function saveDescription(button) {
    const id = button.dataset.toolId;
    const field = document.getElementById('desc-' + id);
    if (!field) {
      return;
    }
    const value = field.value.trim();
    await withBusy(button, async function () {
      try {
        // 显式传 null 表示清除并回退到原始描述；不传才是"不改动"。
        await api('PATCH', '/api/gateways/' + encodeURIComponent(gatewayId())
            + '/tools/' + encodeURIComponent(id), { customDescription: value === '' ? null : value });
        showSuccess('描述已保存', value === '' ? '已清空，回退到下游的原始描述。' : '');
      } catch (e) {
        showError('保存描述失败', e.message);
      }
    });
  }

  async function rotateToken(button) {
    if (!window.confirm('确定轮换访问令牌？\n\n旧令牌立即失效，正在使用它的 Agent 会断开连接。')) {
      return;
    }
    await withBusy(button, async function () {
      try {
        const result = await api('POST',
            '/api/gateways/' + encodeURIComponent(gatewayId()) + '/access-token/rotate');
        showToken(result.accessToken);
        showSuccess('令牌已轮换', '新令牌只显示这一次，请立即复制保存。');
      } catch (e) {
        showError('轮换令牌失败', e.message);
      }
    });
  }

  // ---------------------------------------------------------------- 绑定

  document.addEventListener('click', function (event) {
    const target = event.target.closest('[data-action], [data-copy-target]');
    if (!target) {
      return;
    }
    if (target.dataset.copyTarget) {
      copyFrom(target.dataset.copyTarget, target);
      return;
    }
    switch (target.dataset.action) {
      case 'delete-gateway': deleteGateway(target); break;
      case 'sync-downstream': syncDownstream(target); break;
      case 'delete-downstream': deleteDownstream(target); break;
      case 'save-description': saveDescription(target); break;
      case 'rotate-token': rotateToken(target); break;
      default: break;
    }
  });

  document.addEventListener('change', function (event) {
    if (event.target.dataset && event.target.dataset.action === 'toggle-tool') {
      toggleTool(event.target);
    }
  });

  bindCreateGateway();
  bindGatewayForm();
  bindImportForm();
  bindDownstreamForms();

})();
