import zipfile, pickle, numpy as np, sys
from collections import OrderedDict

class FakeStorage:
    def __init__(self, name): self.__name__ = name

def load_pth(path):
    zf = zipfile.ZipFile(path)
    names = zf.namelist()
    prefix = names[0].split('/')[0]
    class Unp(pickle.Unpickler):
        def find_class(self, module, name):
            if module == 'collections' and name == 'OrderedDict': return OrderedDict
            if name == '_rebuild_tensor_v2': return rebuild
            if name.endswith('Storage'): return FakeStorage(name)
            if module == 'torch' and name == 'Size': return tuple
            return super().find_class(module, name)
        def persistent_load(self, pid):
            _, stype, key, loc, numel = pid
            dt = {'FloatStorage': np.float32, 'HalfStorage': np.float16}[stype.__name__]
            return np.frombuffer(zf.read(f"{prefix}/data/{key}"), dtype=dt)
    def rebuild(storage, offset, size, stride, *a):
        size = tuple(size); stride = tuple(stride)
        return np.lib.stride_tricks.as_strided(storage[offset:], shape=size,
            strides=[s * storage.itemsize for s in stride]).copy()
    obj = Unp(zf.open(f"{prefix}/data.pkl")).load()
    return obj.get('params', obj)

def build(sd, num_conv, num_feat=64, up=4):
    import tensorflow as tf
    from tensorflow import keras
    L = keras.layers
    inp = keras.Input((128, 128, 3), batch_size=1)
    x = inp
    n = num_conv + 1
    for i in range(n):
        w = sd[f'body.{2*i}.weight']; b = sd[f'body.{2*i}.bias']
        conv = L.Conv2D(num_feat, 3, padding='same')
        x = conv(x)
        conv.set_weights([w.transpose(2, 3, 1, 0), b])
        pr = L.PReLU(shared_axes=[1, 2])
        x = pr(x)
        pr.set_weights([sd[f'body.{2*i+1}.weight'].reshape(1, 1, num_feat)])
    w = sd[f'body.{2*n}.weight']; b = sd[f'body.{2*n}.bias']   # last conv
    C = 3
    # torch pixel-shuffle channel = c*r^2 + i*r + j ; tf depth_to_space channel = (i*r+j)*C + c
    perm = np.zeros(C * up * up, dtype=int)
    for c in range(C):
        for i in range(up):
            for j in range(up):
                perm[(i * up + j) * C + c] = c * up * up + i * up + j
    w2 = w[perm].transpose(2, 3, 1, 0); b2 = b[perm]
    conv = L.Conv2D(C * up * up, 3, padding='same')
    x = conv(x)
    conv.set_weights([w2, b2])
    x = L.Lambda(lambda t: tf.nn.depth_to_space(t, up))(x)
    base = L.UpSampling2D(size=up, interpolation='nearest')(inp)
    out = L.Add()([x, base])
    return keras.Model(inp, out)

if __name__ == '__main__':
    import tensorflow as tf
    pth, nconv, outp = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    sd = load_pth(pth)
    print(len(sd), 'tensors; first keys:', list(sd.keys())[:4])
    m = build(sd, nconv)
    conv = tf.lite.TFLiteConverter.from_keras_model(m)
    conv.optimizations = [tf.lite.Optimize.DEFAULT]
    conv.target_spec.supported_types = [tf.float16]
    open(outp, 'wb').write(conv.convert())
    print('saved', outp)
