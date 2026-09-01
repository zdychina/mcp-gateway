import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import StatusBadge from '../src/components/StatusBadge.vue'

describe('网关状态徽章', () => {
  it('四个状态各有自己的样式和解释', () => {
    const cases = [
      ['READY', 'badge-ready'],
      ['DEGRADED', 'badge-degraded'],
      ['UNAVAILABLE', 'badge-unavailable'],
      ['EMPTY', 'badge-empty']
    ] as const

    for (const [status, className] of cases) {
      const badge = mount(StatusBadge, { props: { status } })
      expect(badge.text()).toBe(status)
      expect(badge.classes()).toContain(className)
      // DEGRADED 这类词本身说明不了含义，title 必须带上解释
      expect(badge.attributes('title')).toBeTruthy()
    }
  })
})
