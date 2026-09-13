/// <reference types="vite/client" />
import { Live2DModel } from "pixi-live2d-display/cubism4"
import type { PixiManager } from "./PixiManager"

export const Param = {
  MouthOpenY: "ParamMouthOpenY",
  MouthForm: "ParamMouthForm",
  EyeBallX: "ParamEyeBallX",
  EyeBallY: "ParamEyeBallY",
  AngleX: "ParamAngleX",
  AngleY: "ParamAngleY",
  AngleZ: "ParamAngleZ",
  BodyAngleX: "ParamBodyAngleX",
} as const

export class Live2DManager {
  private _pixi: PixiManager
  private _model: Live2DModel | null = null
  private _currentModelName = ""
  private _scale = 0.18
  private _baseX = 0
  private _baseY = 0

  // Smooth eye tracking state
  private _targetAngle = 0    // rad
  private _targetDist = 0     // 0..1
  private _curAngle = 0
  private _curDist = 0
  private _tickerFn: ((dt: number) => void) | null = null

  constructor(pixi: PixiManager) {
    this._pixi = pixi
    Live2DModel.registerTicker((this._pixi.app.ticker as any).constructor)
  }

  get loaded(): boolean { return this._model !== null }
  get modelName(): string { return this._currentModelName }
  get model(): Live2DModel | null { return this._model }
  get scale(): number { return this._scale }
  get canvasRect(): DOMRect | null {
    return this._pixi.canvas.getBoundingClientRect()
  }

  async loadModel(folderName: string, modelFile?: string, options?: {
    scale?: number
    xMultiplier?: number
    yMultiplier?: number
  }): Promise<void> {
    if (options?.scale !== undefined) this._scale = options.scale
    const xMul = options?.xMultiplier ?? 1 / 2.4
    const yMul = options?.yMultiplier ?? 0.65
    if (this._model) {
      this._pixi.app.stage.removeChild(this._model as any)
      this._model.destroy()
      this._model = null
    }

    const base = import.meta.env.BASE_URL || '/'
    const fileName = modelFile || `${folderName}.model3.json`
    const url = `${base}live2d/${folderName}/${fileName}`

    this._model = await Live2DModel.from(url, { autoInteract: false })
    ;(this._model as any).eventMode = "none"
    ;(this._model as any).interactiveChildren = false

    this._model.scale.set(this._scale)
    this._model.anchor.set(0.5, 0.5)
    this._baseX = this._pixi.width * xMul
    this._baseY = this._pixi.height * yMul
    this._model.x = this._baseX
    this._model.y = this._baseY
    this._pixi.app.stage.addChild(this._model as any)
    this._currentModelName = folderName
    this._startTicker()
  }

  // -- Smooth ticker --
  private _startTicker(): void {
    if (this._tickerFn) return
    this._tickerFn = (dt: number) => {
      const f = Math.min(1, dt * 15)
      let da = this._targetAngle - this._curAngle
      while (da > Math.PI) da -= Math.PI * 2
      while (da < -Math.PI) da += Math.PI * 2
      this._curAngle += da * f
      this._curDist += (this._targetDist - this._curDist) * f

      const nx = Math.cos(this._curAngle) * this._curDist
      const ny = Math.sin(this._curAngle) * this._curDist
      this.setParameter(Param.EyeBallX, nx * 2.5)
      this.setParameter(Param.EyeBallY, ny * 2.5)
      this.setParameter(Param.AngleX, nx * 60)
      this.setParameter(Param.AngleY, ny * 50)
      this.setParameter(Param.AngleZ, nx * ny * -30)
      this.setParameter(Param.BodyAngleX, nx * 25)
    }
    this._pixi.app.ticker.add(this._tickerFn)
  }

  private _stopTicker(): void {
    if (this._tickerFn) {
      this._pixi.app.ticker.remove(this._tickerFn)
      this._tickerFn = null
    }
  }

  lookAt(screenX: number, screenY: number): void {
    const rect = this.canvasRect
    if (!rect || rect.width === 0 || rect.height === 0) return
    const cx = rect.left + rect.width / 2
    const cy = rect.top + rect.height * 0.3
    const dx = screenX - cx
    const dy = screenY - cy
    const maxR = Math.max(window.innerWidth, window.innerHeight) * 0.3
    const r = Math.min(Math.sqrt(dx * dx + dy * dy), maxR)
    this._targetAngle = Math.atan2(dy, dx)
    this._targetDist = Math.min(r / maxR * 1.5, 1)
    console.log("lookAt - angle:", this._targetAngle, "dist:", this._targetDist)
  }

  // -- Parameters --
  private getCore(): any {
    return (this._model?.internalModel as any)?.coreModel ?? null
  }

  setParameter(id: string, value: number): void {
    const core = this.getCore()
    if (core && typeof core.setParameterValueById === "function") {
      core.setParameterValueById(id, value)
    }
  }

  setMouthOpen(open: number): void {
    this.setParameter(Param.MouthOpenY, open)
  }

  // -- Motions --
  playMotion(group: string, index: number): void {
    this._model?.motion(group, index)
  }

  randomMotion(): void {
    if (!this._model) return
    this._model.motion("Tap", Math.floor(Math.random() * 5))
  }

  setExpression(name: string): void {
    this._model?.expression(name)
  }

  setScale(s: number): void {
    this._scale = s
    this._model?.scale.set(s)
  }

  setPosition(x: number, y: number): void {
    if (this._model) {
      this._model.x = x
      this._model.y = y
    }
  }

  hitTest(x: number, y: number): string[] {
    return this._model?.hitTest(x, y) ?? []
  }

  onHit(callback: (hitAreas: string[]) => void): void {
    this._model?.on("hit", (areas) => callback(areas as string[]))
  }

  center(): void {
    if (this._model) {
      this._model.x = this._baseX
      this._model.y = this._baseY
    }
  }

  destroy(): void {
    this._stopTicker()
    if (this._model) {
      this._pixi.app.stage.removeChild(this._model as any)
      this._model.destroy()
      this._model = null
    }
  }
}

