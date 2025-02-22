import argparse
import json
import re

import torch
import torch.nn as nn
import torch.optim as optim
import torch.nn.functional as F
import random
import numpy as np
import pandas as pd
from torch.utils.data import Dataset, DataLoader
from config2 import train_files, test_files

import os
os.environ['CUDA_VISIBLE_DEVICES'] = '0'
cuda = str(os.environ['CUDA_VISIBLE_DEVICES'])

# data path
data_dir = "./data/"
dataset_address = [data_dir,data_dir]

parser = argparse.ArgumentParser()

parser.add_argument("--dataset", default=dataset_address[0])
parser.add_argument("--test_dataset", default=dataset_address[1])
parser.add_argument("--use_cuda", default=True)
parser.add_argument("--seed", default=2024)
parser.add_argument("--batch_size", default=128)
parser.add_argument("--epoch_num", default=50)
parser.add_argument("--learning_rate", default=1e-3)
parser.add_argument("--data_seg", default=30)
parser.add_argument("--lab_seg", default=5)
parser.add_argument("--sample_rate", default=1)

args = parser.parse_args()

random.seed(args.seed)
np.random.seed(args.seed)
torch.manual_seed(args.seed)
torch.cuda.manual_seed(args.seed)
torch.cuda.manual_seed_all(args.seed)
args.use_cuda = args.use_cuda and torch.cuda.is_available()
args.device = 'cuda' if args.use_cuda else 'cpu'
print(args.device)
args.data_len = args.data_seg // args.sample_rate

# features for the three towers
args.network_feature = ["rtt", "loss_rate", "dns_rate", "dns_latency", "ul_bw", "dl_bw"]
args.radio_feature = ["rsrp", "snr", "rat"]
args.bs_feature = ["handover", "dwell_time"]
args.label = ["is_vss"]

def load_data(data_path, training=True):
    data_tmp = pd.read_csv(data_path)
    """
    the csv is formulated as below:
    | features                      | labels                                |  
    | 30 x 11 array                 | 1 x 1 array                           |
    | 30 for historical window size | 1 for VSS in the subsequent 5 seconds |
    | 11 for the number of features | 0 for non-VSS                         |
    """
    return data_tmp['features'].tolist(), data_tmp['labels'].tolist(), len(data_tmp)

class SigDataset(Dataset):
    def __init__(self, data, label, training=True):

        self.input = data
        self.label = label
        self.training = training

        c = self.input.shape[2]

        # normalization
        if training:
            args.train_mean = np.zeros(c)
            args.train_std = np.zeros(c)
            for i in range(c):
                channel = self.input[:, :, i]
                args.train_mean[i] = np.nanmean(channel)
                args.train_std[i] = np.nanstd(channel)
                train_mean = args.train_mean[i]
                train_std = args.train_std[i]
                self.input[:,:, i] = (channel - train_mean) / (train_std + 1e-5)
        if not training:
            for i in range(c):
                channel = self.input[:, :, i]
                train_mean = args.train_mean[i]
                train_std = args.train_std[i]
                self.input[:,:, i] = (channel - train_mean) / (train_std + 1e-5)

    def __len__(self):
        return len(self.label)

    def __getitem__(self, idx):
        if self.training:
            sample = self.input[idx], self.label[idx]
        else:
            sample = self.input[idx], self.label[idx]
        return sample


# load_data
train_data, train_label, data_len = load_data(args.dataset, training=True)
test_data, test_label, data_len = load_data(args.test_dataset, training=False)
data_index = np.random.permutation(test_label.shape[0])
dev_idx = round(test_label.shape[0]*0.5)
dev_data_index, test_data_index = \
    data_index[:dev_idx], data_index[dev_idx:]
dev_data, dev_label = test_data[dev_data_index], test_label[dev_data_index]
test_data, test_label = test_data[test_data_index], test_label[test_data_index]

train_data = SigDataset(train_data, train_label, training=True)
dev_data = SigDataset(dev_data, dev_label, training=False)
test_data = SigDataset(test_data, test_label, training=False)

train_data = DataLoader(train_data, batch_size=args.batch_size, shuffle=True)
dev_data = DataLoader(dev_data, batch_size=args.batch_size, shuffle=False)
test_data = DataLoader(test_data, batch_size=args.batch_size, shuffle=False)


import math
class Model(nn.Module):

    def __init__(self, dim_in, dropout_rate=0.0):
        super(Model, self).__init__()

        self.dim_in = dim_in
        self.hidden_dim = 32
        self.pe = self.create_positional_encoding(args.data_len, self.dim_in)

        # three feature towers
        self.lstm1 = nn.LSTM(
            input_size=len(args.network_feature),
            hidden_size=self.hidden_dim//2,
            batch_first=True,
            bidirectional=True,
            dropout=dropout_rate,
            num_layers=3
        )
        self.lstm2 = nn.LSTM(
            input_size=len(args.radio_feature),
            hidden_size=self.hidden_dim//2,
            batch_first=True,
            bidirectional=True,
            dropout=dropout_rate,
            num_layers=3
        )
        self.lstm3 = nn.LSTM(
            input_size=len(args.bs_feature),
            hidden_size=self.hidden_dim//2,
            batch_first=True,
            bidirectional=True,
            dropout=dropout_rate,
            num_layers=3
        )
        self.attention_layer = nn.Linear(self.hidden_dim*3, 1)
        self.linear_layer = nn.Linear(self.hidden_dim*3, 1)
        self.sigmoid = nn.Sigmoid()



    def forward(self, x, ft=None, labels=None):
        b = x.shape[0]
        x = x + self.pe.repeat(b, 1, 1)
        x[torch.isnan(x)] = 0
        x_network = x[:,:,:len(args.network_feature)]
        x_radio = x[:,:,len(args.network_feature):len(args.network_feature)+len(args.radio_feature)]
        x_bs = x[:,:,-len(args.bs_feature):]

        # feature extraction with three towers
        out1, _ = self.lstm1(x_network) # lstm_out: (batch_size, seq_len, hidden_dim)
        out2, _ = self.lstm2(x_radio) # lstm_out: (batch_size, seq_len, hidden_dim)
        out3, _ = self.lstm3(x_bs) # lstm_out: (batch_size, seq_len, hidden_dim)
        concat_out = torch.cat([out1, out2, out3]
        # attention layer
        attn_weights = self.attention_layer(concat_out, dim=-1))
        attn_weights = torch.softmax(attn_weights, dim=1) 
        attn_output = torch.sum(attn_weights * concat_out, dim=1)
        out = self.linear_layer(attn_output)
        out = self.sigmoid(out)
        return out

    def create_positional_encoding(self, seq_len, d_model):
        # position encoding for LSTM
        pe = torch.zeros(seq_len, d_model).to(args.device)
        position = torch.arange(0, seq_len, dtype=torch.float).unsqueeze(1)
        div_term = torch.exp(torch.arange(0, d_model, 2).float() * (-math.log(10000.0) / d_model))

        pe[:, 0::2] = torch.sin(position * div_term)

        if d_model % 2 == 1:
            div_term = div_term[:-1]
        pe[:, 1::2] = torch.cos(position * div_term)

        return pe.unsqueeze(0)




# train the model
model = Model(dim_in=args.num_feature)
model.to(args.device)
bce_loss = nn.BCELoss()
optim = optim.AdamW(model.parameters(), lr=args.learning_rate)
scheduler = torch.optim.lr_scheduler.StepLR(optim, 1, gamma=0.99)
max_f1, max_f1_acc = 0, 0
best_epoch = -1

for step in range(args.epoch_num):
    losses=[]
    results = np.zeros((0, 2))

    ### train
    model.train()
    for i, batch_data in enumerate(train_data):
        input, label = [tmp.to(args.device) for tmp in batch_data]
        batch_size=label.size(0)

        model.zero_grad()

        out = model(input)
        loss = bce_loss(out, label)
        losses.append(loss.data.cpu().numpy() if args.use_cuda else loss.data.numpy()[0])
        loss.backward()

        torch.nn.utils.clip_grad_norm_(model.parameters(), 0.5)
        optim.step()

        out_lab = (out>=0.5)+0
        label = (label>=0.5)+0
        results = np.r_[results, np.c_[out_lab.cpu(), label.cpu()]]

    scheduler.step()
    out_lab = results[:,0]
    label = results[:,1]
    print(np.sum(out_lab==1))
    print(np.sum(label==1))
    tp = np.sum(out_lab[label==1]==1)
    tn = np.sum(out_lab[label==0]==0)
    fp = np.sum(out_lab[label==0]==1)
    fn = np.sum(out_lab[label==1]==0)
    precision = tp/(tp+fp)
    recall = tp/(tp+fn)
    f1 = 2 * (precision * recall) / (precision + recall)
    print("Step",step," batches",i," :")
    print("Train-\t",
          f"loss:{round(float(np.mean(losses)), 4)}",
          f"F1:{round(f1, 3)}",
          f"acc:{round((tp+tn)/(tp+fp+tn+fn), 3)}",
          f"precision:{round(precision, 3)}",
          f"recall:{round(recall, 3)}")

    ### validation
    losses=[]
    results = np.zeros((0, 2))
    model.eval()
    with torch.no_grad():
        for i, batch_data in enumerate(dev_data):
            input, label = [tmp.to(args.device) for tmp in batch_data]
            model.zero_grad()
            out = model(input)
            loss = bce_loss(out, label)
            losses.append(loss.data.cpu().numpy() if args.use_cuda else loss.data.numpy()[0])
            out_lab = (out>=0.5)+0
            label = (label>=0.5)+0
            results = np.r_[results, np.c_[out_lab.cpu(), label.cpu()]]

    out_lab = (results[:,0]>=0.5)+0
    label = (results[:,1]>=0.5)+0
    tp = np.sum(out_lab[label==1]==1)
    tn = np.sum(out_lab[label==0]==0)
    fp = np.sum(out_lab[label==0]==1)
    fn = np.sum(out_lab[label==1]==0)
    acc = (tp+tn)/(tp+fp+tn+fn)
    precision = tp/(tp+fp)
    recall = tp/(tp+fn)
    f1 = 2 * (precision * recall) / (precision + recall)
    print(np.sum(out_lab == 1))
    print(np.sum(label == 1))
    print("Dev-\t",
          f"loss:{round(float(np.mean(losses)), 4)}",
          f"F1:{round(f1, 3)}",
          f"acc:{round(acc, 3)}",
          f"precision:{round(precision, 3)}",
          f"recall:{round(recall, 3)}")

    if max_f1<=f1:
        max_f1=f1
        max_f1_acc=acc
        best_epoch = step
        torch.save(model,f"models/model"+cuda+".pkl")

print(f"best epoch: {best_epoch} max F1:{max_f1}  acc: {max_f1_acc}")


# load the best model and test
model.load_state_dict(torch.load(f"models/model"+cuda+".pkl").state_dict())
model.to(args.device)
model.eval()

losses=[]
results = np.zeros((0,2))
with torch.no_grad():
    for i, batch_data in enumerate(test_data):
        input, feature, label = [tmp.to(args.device) for tmp in batch_data]
        model.zero_grad()
        loss = bce_loss(out, label)
        losses.append(loss.data.cpu().numpy() if args.use_cuda else loss.data.numpy()[0])
        out_lab = (out>=0.5)+0
        label = (label>=0.5)+0
        results = np.r_[results, np.c_[out_lab.cpu(), label.cpu()]]

    out_lab = (results[:,0]>=0.5)+0
    label = (results[:,1]>=0.5)+0
    tp = np.sum(out_lab[label==1]==1)
    tn = np.sum(out_lab[label==0]==0)
    fp = np.sum(out_lab[label==0]==1)
    fn = np.sum(out_lab[label==1]==0)
    n_acc = tn/(tn+fn)
    precision = tp/(tp+fp)
    recall = tp/(tp+fn)
    fpr = fp/(tn+fp)
    fnr = fn/(tp+fn)
    f1 = 2 * (precision * recall) / (precision + recall)
    acc=(tp + tn) / (tp + fp + tn + fn)
    print("Test-")
    print("loss n_acc precision recall fpr fnr acc F1")
    print(round(float(np.mean(losses)), 3), round(n_acc, 3), round(precision, 3), round(recall, 3), round(fpr, 3), round(fnr, 3), round(acc, 3), round(f1, 3))
    print(f"Result: {round(f1, 3)}")
    np.save("results.npy", results)
